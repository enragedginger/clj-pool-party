(ns com.github.enragedginger.clj-pool-party.core-test
  (:require [clojure.test :refer :all]
            [com.github.enragedginger.clj-pool-party.core :as sut]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen])
  (:import (clojure.lang ExceptionInfo Ref)
           (java.util.concurrent Executors)))

(def gen-non-pos-nat (gen/fmap - gen/nat))
(def gen-pos-nat (gen/fmap inc gen/nat))

(defn- run-vthread-tasks [task-fn times]
  (let [tasks (repeatedly times (constantly task-fn))]
    (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
      (->> (.invokeAll exec tasks)
        (map (memfn get))
        (doall)))))

;;non-positive max size values should result in an assertion error
(defspec pool-max-size-pos 10
  (prop/for-all [non-pos-max-size gen-non-pos-nat]
    (try
      (sut/build-pool (constantly 5) non-pos-max-size)
      false
      (catch AssertionError e
        true))))

(defspec wait-timeout-pos 10
  (prop/for-all [max-size gen-pos-nat
                 non-pos-timeout gen-non-pos-nat]
    (try
      (sut/build-pool (constantly 5) max-size {:wait-timeout-ms non-pos-timeout})
      false
      (catch AssertionError e
        true))))

;;create n+1 tasks where each task sleeps for slightly longer than the wait-timeout-ms value
;;this should create a scenario wherein exactly one task times out
(defspec wait-timeout-exceeded 100
  (prop/for-all [max-size (gen/no-shrink gen-pos-nat)
                 wait-timeout-ms (gen/no-shrink gen-pos-nat)]
    (let [gen-fn (constantly 5)
          pool-ref (sut/build-pool gen-fn max-size {:wait-timeout-ms wait-timeout-ms})
          task-fn (fn []
                    (try
                      (sut/with-object pool-ref
                        (fn [obj]
                          (Thread/sleep (+ 2 wait-timeout-ms))))
                      false
                      (catch ExceptionInfo e
                        (if (-> e ex-data :wait-timeout-ms (= wait-timeout-ms))
                          true
                          (throw e)))))
          results (run-vthread-tasks task-fn (inc max-size))
          wait-timeout-task-count (->> results
                                    (filter identity)
                                    (count))]
      (= 1 wait-timeout-task-count))))

;;Given a generator function that produces the sequence of natural numbers (but starting at zero)
;;borrowing up to some multiple of the max-size should never result in a value greater than
;;the max-size
(defspec max-size-not-exceeded 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size)
          times (mod n 3)]
      (dotimes [idx (* (inc times) max-size)]
        (sut/with-object pool-ref identity))
      (<= @id-atom max-size))))

;;Same test as above but we test parallel borrowing with pmap
(defspec max-size-not-exceeded-pmap 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size)
          times (mod n 3)]
      (dorun
        (pmap (fn [idx]
                (sut/with-object pool-ref
                  (fn [obj]
                    (Thread/sleep 10))))
          (range (* (inc times) max-size))))
      (<= @id-atom max-size))))

;;Same test as above but we test with a virtual thread pool
(defspec max-size-not-exceeded-vthreads 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size)
          times (mod n 3)
          task-fn (fn []
                    (sut/with-object pool-ref
                      (fn [obj]
                        (Thread/sleep 10))))
          results (run-vthread-tasks task-fn (* (inc times) max-size))]
      (<= 1 @id-atom max-size))))

;;given our natural numbers (starting at zero) generator function
;;and a check-on-return health-check-fn that designates only even numbers as "healthy"
;;all objects in the pool after a number of borrows should be even numbers
(defspec health-check-fn-on-return 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          health-check-fn (comp even? :id)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:return-health-check-fn health-check-fn})
          times (mod n 3)
          task-fn (fn []
                    (sut/with-object pool-ref
                      (fn [obj]
                        (Thread/sleep 10))))
          results (run-vthread-tasks task-fn (* (inc times) max-size))]
      (->> @pool-ref :objects
        (map #(-> % second :obj :id))
        (every? even?)))))

;;given a sequence of odd numbers (starting at 1) generator function
;;and a check-on-borrow health-check-fn that designates only even numbers as "healthy"
;;all objects in the pool after a number of borrows should be odd numbers
(defspec health-check-fn-on-borrow 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 1)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom + 2)]
                            (atom {:id new-id})))
          health-check-fn #(-> % deref :id even?)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:borrow-health-check-fn health-check-fn})
          times (mod n 3)
          task-fn (fn []
                    (sut/with-object pool-ref
                      (fn [obj]
                        (assert (-> obj deref :id odd?)))))
          results (run-vthread-tasks task-fn (* (inc times) max-size))]
      (->> @pool-ref :objects
        (map #(-> % second :obj deref :id))
        (every? odd?)))))

;;given a sequence of odd numbers (starting at 1) generator function
;;and a check-on-borrow + check-on-return health-check-fn that designates only even numbers as "healthy"
;;the object pool should be empty
(defspec health-check-fn-on-borrow-and-return 100
  (prop/for-all [max-size gen-pos-nat
                 n gen/nat]
    (let [id-atom (atom 1)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom + 2)]
                            (atom {:id new-id})))
          health-check-fn #(-> % deref :id even?)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:borrow-health-check-fn health-check-fn
                                                                :return-health-check-fn health-check-fn})
          times (mod n 3)
          task-fn (fn []
                    (sut/with-object pool-ref
                      (fn [obj]
                        (assert (-> obj deref :id odd?)))))
          results (run-vthread-tasks task-fn (* (inc times) max-size))]
      (->> @pool-ref :objects empty?))))

(defspec evict-all-clears-pool 100
  (prop/for-all [max-size gen-pos-nat]
    (let [close-called-counter (atom 0)
          gen-fn (constantly 5)
          close-fn (fn [obj]
                     (assert (= 5 obj))
                     (swap! close-called-counter inc))
          ^Ref pool-ref (sut/build-pool gen-fn max-size {:close-fn close-fn})
          task-fn (fn []
                    (sut/with-object pool-ref
                      (fn [obj]
                        (Thread/sleep 10)
                        (assert (= 5 obj)))))]
      (run-vthread-tasks task-fn (* 2 max-size))
      (sut/evict-all pool-ref)
      (= @close-called-counter max-size))))

(comment
  (run-tests *ns*))
