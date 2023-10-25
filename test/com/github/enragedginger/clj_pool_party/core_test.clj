(ns com.github.enragedginger.clj-pool-party.core-test
  (:require [clojure.test :refer :all]
            [com.github.enragedginger.clj-pool-party.core :as sut]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen])
  (:import (clojure.lang Ref)
           (java.util.concurrent Executors)))

;;if health-check-fn is specified, then at least one of :check-on-borrow? or :check-on-return? need
;;to be specified. Otherwise, throw an error.
(deftest health-check-fn-booleans
  (let [gen-fn (constantly 5)
        max-size 5]
    (is (thrown? AssertionError
          (sut/build-pool gen-fn max-size {:health-check-fn identity})))
    (is (thrown? AssertionError
          (sut/build-pool gen-fn max-size {:health-check-fn identity :check-on-borrow? false :check-on-return? false})))
    (sut/build-pool gen-fn max-size {:health-check-fn identity :check-on-borrow? true :check-on-return? false})
    (sut/build-pool gen-fn max-size {:health-check-fn identity :check-on-borrow? false :check-on-return? true})
    (sut/build-pool gen-fn max-size {:health-check-fn identity :check-on-borrow? true :check-on-return? true})))

;;non-positive max size values should result in an assertion error
(defspec pool-max-size-pos 10
  (prop/for-all [max-size (gen/such-that #(<= % 0) gen/small-integer)]
    (try
      (sut/build-pool (constantly 5) max-size)
      false
      (catch AssertionError e
        true))))

;;Given a generator function that produces the sequence of natural numbers (but starting at zero)
;;borrowing up to some multiple of the max-size should never result in a value greater than
;;the max-size
(defspec max-size-not-exceeded 100
  (prop/for-all [max-size (gen/such-that pos? gen/nat)
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
  (prop/for-all [max-size (gen/such-that pos? gen/nat)
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
  (prop/for-all [max-size (gen/such-that pos? gen/nat)
                 n gen/nat]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size)
          times (mod n 3)
          vthread-fn (fn []
                       (sut/with-object pool-ref
                         (fn [obj]
                           (Thread/sleep 10))))]
      (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
        (dotimes [idx (* (inc times) max-size)]
          (let [vthread (Thread. vthread-fn)]
            (.submit exec vthread))))
      (<= 1 @id-atom max-size))))

;;given our natural numbers (starting at zero) generator function
;;and a check-on-return health-check-fn that designates only even numbers as "healthy"
;;all objects in the pool after a number of borrows should be even numbers
(defspec health-check-fn-on-return 100
  (prop/for-all [max-size (gen/no-shrink (gen/such-that pos? gen/nat))
                 n (gen/no-shrink gen/nat)]
    (let [id-atom (atom 0)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom inc)]
                            {:id new-id}))
          health-check-fn (comp even? :id)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:health-check-fn health-check-fn
                                                                :check-on-return? true})
          times (mod n 3)
          vthread-fn (fn []
                       (sut/with-object pool-ref
                         (fn [obj]
                           (Thread/sleep 10))))]
      (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
        (dotimes [idx (* (inc times) max-size)]
          (let [vthread (Thread. vthread-fn)]
            (.submit exec vthread))))
      (->> @pool-ref :objects
        (map #(-> % second :obj :id))
        (every? even?)))))

;;given a sequence of odd numbers (starting at 1) generator function
;;and a check-on-borrow health-check-fn that designates only even numbers as "healthy"
;;all objects in the pool after a number of borrows should be odd numbers
(defspec health-check-fn-on-borrow 100
  (prop/for-all [max-size (gen/no-shrink (gen/such-that pos? gen/nat))
                 n (gen/no-shrink gen/nat)]
    (let [id-atom (atom 1)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom + 2)]
                            (atom {:id new-id})))
          health-check-fn #(-> % deref :id even?)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:health-check-fn health-check-fn
                                                                :check-on-borrow? true})
          times (mod n 3)
          vthread-fn (fn []
                       (sut/with-object pool-ref
                         (fn [obj]
                           (assert (-> obj deref :id odd?)))))]
      (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
        (dotimes [idx (* (inc times) max-size)]
          (let [vthread (Thread. vthread-fn)]
            (.submit exec vthread))))
      (->> @pool-ref :objects
        (map #(-> % second :obj deref :id))
        (every? odd?)))))

;;given a sequence of odd numbers (starting at 1) generator function
;;and a check-on-borrow + check-on-return health-check-fn that designates only even numbers as "healthy"
;;the object pool should be empty
(defspec health-check-fn-on-borrow-and-return 100
  (prop/for-all [max-size (gen/no-shrink (gen/such-that pos? gen/nat))
                 n (gen/no-shrink gen/nat)]
    (let [id-atom (atom 1)
          sample-gen-fn (fn []
                          (let [new-id (swap! id-atom + 2)]
                            (atom {:id new-id})))
          health-check-fn #(-> % deref :id even?)
          ^Ref pool-ref (sut/build-pool sample-gen-fn max-size {:health-check-fn health-check-fn
                                                                :check-on-borrow? true
                                                                :check-on-return? true})
          times (mod n 3)
          vthread-fn (fn []
                       (sut/with-object pool-ref
                         (fn [obj]
                           (assert (-> obj deref :id odd?)))))]
      (with-open [exec (Executors/newVirtualThreadPerTaskExecutor)]
        (dotimes [idx (* (inc times) max-size)]
          (let [vthread (Thread. vthread-fn)]
            (.submit exec vthread))))
      (->> @pool-ref :objects empty?))))
