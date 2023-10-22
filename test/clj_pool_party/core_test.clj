(ns clj-pool-party.core-test
  (:require [clojure.test :refer :all]
            [clj-pool-party.core :as sut]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen])
  (:import (clojure.lang Ref)
           (java.util.concurrent Executors)))

;;non-positive max size values should result in an assertion error
(defspec pool-max-size-pos 10
  (prop/for-all [max-size (gen/such-that #(<= % 0) gen/small-integer)]
    (try
      (sut/build-pool (constantly 5) max-size)
      false
      (catch AssertionError e
        true))))

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