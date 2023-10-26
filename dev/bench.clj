(ns bench
  (:require [com.github.enragedginger.clj-pool-party.core :as pool-party]
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

(defn multi-checkout []
  (let [gen-fn (constantly 5)
        max-size 100
        pool (pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (pool-party/with-object pool identity))]
    (run-vthread-tasks task-fn (* 2 max-size))))

(defn multi-checkout-map-pool-party []
  (let [gen-fn (constantly 5)
        max-size 100
        pool (map-pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (map-pool-party/with-object pool identity))]
    (run-vthread-tasks task-fn (* 2 max-size))))

(defn multi-checkout-og-pool-party []
  (let [gen-fn (constantly 5)
        max-size 100
        pool (og-pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (og-pool-party/with-object pool identity))]
    (run-vthread-tasks task-fn (* 2 max-size))))

(comment
  (bench (multi-checkout))
  (bench (multi-checkout-map-pool-party))
  (bench (multi-checkout-og-pool-party))
  )