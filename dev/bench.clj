(ns bench
  (:require [com.github.enragedginger.clj-pool-party.core :as pool-party])
  (:import (java.util.concurrent Executors))
  (:use [criterium.core]))

(defn- run-vthread-tasks [task-fn times]
  (let [tasks (repeatedly times (constantly task-fn))]
    (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
      (->> (.invokeAll exec tasks)
        (map (memfn get))
        (doall)))))

(defn multi-checkout []
  (let [gen-fn (constantly 5)
        max-size 100
        pool (pool-party/build-pool gen-fn max-size {})
        task-fn (fn []
                  (pool-party/with-object pool identity))]
    (run-vthread-tasks task-fn (* 2 max-size))))

(comment
  (quick-bench (multi-checkout)))