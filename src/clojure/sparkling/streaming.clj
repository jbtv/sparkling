;; ## EXPERIMENTAL
;;
;; This is a partial and mostly untested implementation of
;; Spark Streaming; consider it a work in progress.
;;
(ns sparkling.streaming
  (:refer-clojure :exclude [map filter time print union count reduce])
  (:require [sparkling.api :as s]
            [sparkling.conf :as conf]
            [sparkling.utils :as u]
            [sparkling.scalaInterop :as scala]
            [sparkling.destructuring :refer [optional-of optional-or-nil] ]
            [sparkling.function :refer [flat-map-function function function2 function3 pair-function void-function]])
  (:import [org.apache.spark.streaming.api.java JavaStreamingContext JavaDStream JavaPairDStream]
           [org.apache.spark.streaming.kafka KafkaUtils]
           [org.apache.spark.streaming Duration Time]
           [sparkling.scalaInterop ScalaFunction0]
           [com.google.common.base Optional]
           [scala Tuple2]))

(defn- ftruthy?
  [f]
  (fn [x] (u/truthy? (f x))))


(defn untuple [^Tuple2 t]
  (let [v (transient [])]
    (conj! v (._1 t))
    (conj! v (._2 t))
    (persistent! v)))

(defn duration [ms]
  (Duration. ms))

(defn time [ms]
  (Time. ms))

(defn streaming-context
  "conf can be a SparkConf or JavaSparkContext"
  [conf batch-duration]
  (JavaStreamingContext. conf (duration batch-duration)))

(defn get-or-create-streaming-context
  "Either recreate a StreamingContext from checkpoint data, if such exists, or create a new StreamingContext by calling create-fn. Returns the StreamingContext."
  [checkpoint-path create-fn]
  (assert (instance? java.lang.String checkpoint-path) "checkpoint path should be a string" )
  (assert (instance? org.apache.spark.api.java.function.Function0 (proxy [org.apache.spark.api.java.function.Function0] [] 
                                                                    (call [& _] (create-fn))))
          "create-fn should be coercable into a Function0"
          )
  (JavaStreamingContext/getOrCreate checkpoint-path (proxy [org.apache.spark.api.java.function.Function0] [] 
                                                      (call [& _] (let [zz9 (create-fn)]
                                                                    (assert (instance? JavaStreamingContext zz9) (str "zz9 isn't a streaming context, it's a " (type zz9)))
                                                                    zz9
                                                                    )))))

(defn local-streaming-context [app-name duration]
  (let [conf (-> (conf/spark-conf)
                 (conf/master "local")
                 (conf/app-name app-name))]
    (streaming-context conf duration)))

(defmulti checkpoint (fn [context arg] (class arg)))
(defmethod checkpoint String [streaming-context path] (.checkpoint streaming-context path))
(defmethod checkpoint Long [dstream interval] (.checkpoint dstream (duration interval)))

(defn text-file-stream [context file-path]
  (.textFileStream context file-path))

(defn socket-text-stream [context ip port]
  (.socketTextStream context ip port))

(defn kafka-stream [& {:keys [streaming-context zk-connect group-id topic-map]}]
  (KafkaUtils/createStream streaming-context zk-connect group-id (into {} (for [[k, v] topic-map] [k (Integer. v)]))))

(defn kafka-direct-stream [streaming-context & {:keys [key-class val-class key-decoder-class val-decoder-class kafka-params topics starting-from]
                                                :or {key-class String
                                                     val-class String
                                                     key-decoder-class kafka.serializer.StringDecoder
                                                     val-decoder-class kafka.serializer.StringDecoder
                                                     kafka-params {"metadata.broker.list" "localhost:9092"}}}]
  (println "kafka dstream starting-from " starting-from)
  (if starting-from
    (let [x (KafkaUtils/createDirectStream streaming-context key-class val-class key-decoder-class val-decoder-class
                                           scala.Tuple2
                                           kafka-params
                                           (into {} (for [[[t p] o] starting-from]
                                                      [(kafka.common.TopicAndPartition. t p) (long o)]) ) ;; FIXME dangerous... need to fix type in kafka table to be bigint
                                           (function (fn [m] (s/tuple (.key m) (.value m)))))]
      (org.apache.spark.streaming.api.java.JavaPairInputDStream/fromInputDStream
       (.dstream x)
       (.Any scala.reflect.ClassTag$/MODULE$)
       (.Any scala.reflect.ClassTag$/MODULE$)))
    (KafkaUtils/createDirectStream streaming-context key-class val-class key-decoder-class val-decoder-class kafka-params topics)))

(defn flat-map      [dstream f] (.flatMap   dstream (flat-map-function f)))
(defn map             [dstream f] (.map       dstream (function f)))
(defn filter             [dstream f] (.filter    dstream (function (ftruthy? f))))
(defn map-values    [dstream f] (.mapValues dstream (function f)))
(defn filter-values [dstream f] (filter          dstream #(-> % ._2 f)))
(defn map-to-pair   [dstream f] (.mapToPair dstream (pair-function f)))

(defn count         [dstream] (.count      dstream))
(defn group-by-key  [dstream] (.groupByKey dstream))

(defn reduce [dstream f]
  "Return a new DStream in which each RDD has a single element
  generated by reducing each RDD of this DStream."
  (.reduce dstream (function2 f)))

(defn reduce-by-key [dstream f]
  "Call reduceByKey on dstream of type JavaDStream or JavaPairDStream"
  (if (instance? JavaDStream dstream)
    ;; JavaDStream doesn't have a .reduceByKey so cast to JavaPairDStream first
    (-> dstream
      (.mapToPair (pair-function identity))
      (.reduceByKey (function2 f))
      (.map (function untuple)))
    ;; if it's already JavaPairDStream, we're good
    (-> dstream
        (.reduceByKey (function2 f))
        (.map (function untuple)))))

(defn update-state-by-key [dstream f]
  (letfn [(wrapped-f [seq opt-state]
            (optional-of
             (f seq (optional-or-nil opt-state))))]
    (.updateStateByKey dstream (function2 wrapped-f))))

;; ## Transformations
;;
(defn transform                [dstream f]              (.transform           dstream (function2 f)))
(defn transform-to-pair        [dstream f]              (.transformToPair     dstream (function2 f)))
(defn transform-with           [dstream other-stream f] (.transformWith       dstream other-stream (function3 f)))
(defn transform-with-to-pair   [dstream other-stream f] (.transformWithToPair dstream other-stream (function3 f)))
(defn repartition              [dstream num-partitions] (.repartition         dstream (Integer. num-partitions)))
(defn union                    [dstream other-stream]   (.union               dstream other-stream))
(defn join                     [dstream other-stream]   (.join                dstream other-stream))
(defn left-outer-join          [dstream other-stream]   (.leftOuterJoin       dstream other-stream)) ;; FIXME support all the arities

;; ## Window Operations
;;
(defn window [dstream window-length slide-interval]
  (.window dstream (duration window-length) (duration slide-interval)))

(defn count-by-window [dstream window-length slide-interval]
  (.countByWindow dstream (duration window-length) (duration slide-interval)))

(defn group-by-key-and-window [dstream window-length slide-interval]
  (-> dstream
      (.mapToPair (pair-function identity))
      (.groupByKeyAndWindow (duration window-length) (duration slide-interval))
      (.map (function untuple))))

(defn reduce-by-window [dstream f f-inv window-length slide-interval]
  (.reduceByWindow dstream (function2 f) (function2 f-inv) (duration window-length) (duration slide-interval)))

(defn reduce-by-key-and-window [dstream f window-length slide-interval]
  (-> dstream
      (.mapToPair (pair-function identity))
      (.reduceByKeyAndWindow (function2 f) (duration window-length) (duration slide-interval))
      (.map (function untuple))))


;; ## Actions
;;
(defn print
  ( [dstream]       (.print dstream))
  ( [dstream count] (.print dstream count)))

(defn foreach-rdd [dstream f]
  (.foreachRDD dstream (function2 f)))


;; ## Output
;;
(defn save-as-text-files
  ;;TODO: check whether first param is of type
  ;;DStream or just let an exception be thrown?
  ([dstream prefix suffix]
    (.saveAsTextFiles dstream prefix suffix))
  ([dstream prefix]
   (.saveAsTextFiles dstream prefix)))
