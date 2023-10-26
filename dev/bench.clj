(ns bench
  (:require [com.github.enragedginger.clj-pool-party.core :as pool-party]
            [double-array-pool-party :as double-array-pool-party]
            [map-pool-party :as map-pool-party]
            [og-pool-party :as og-pool-party])
  (:import (java.util.concurrent Executors))
  (:use [criterium.core]))

(defn- run-vthread-tasks [task-fn times]
  (let [tasks (repeatedly times (constantly task-fn))]
    (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
      (->> (.invokeAll exec tasks)
        (map (memfn get))
        (dorun)))))

(def gen-fn (constantly 5))
(def max-size 100)

(defn multi-checkout []
  (let [pool (pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (pool-party/with-object pool identity))]
    (bench
      (run-vthread-tasks task-fn (* 2 max-size)))))

(defn multi-checkout-map-pool-party []
  (let [pool (map-pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (map-pool-party/with-object pool identity))]
    (bench
      (run-vthread-tasks task-fn (* 2 max-size)))))

(defn multi-checkout-og-pool-party []
  (let [pool (og-pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (og-pool-party/with-object pool identity))]
    (bench
      (run-vthread-tasks task-fn (* 2 max-size)))))

(defn multi-checkout-double-array-pool-party []
  (let [pool (double-array-pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (double-array-pool-party/with-object pool identity))]
    (bench
      (run-vthread-tasks task-fn (* 2 max-size)))))

(comment
  (multi-checkout)
  (multi-checkout-map-pool-party)
  (multi-checkout-og-pool-party)
  (multi-checkout-double-array-pool-party)
  )