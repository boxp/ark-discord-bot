(ns ark-discord-bot.rcon.protocol-test
    "Tests for RCON binary protocol implementation."
    (:require [ark-discord-bot.rcon.protocol :as protocol]
              [clojure.test :refer [deftest is testing]]))

(deftest test-pack-packet
  (testing "pack-packet creates correct little-endian structure"
    (let [packet (protocol/pack-packet 1 3 "test")]
      ;; Packet structure: 4-byte size + 4-byte id + 4-byte type + body + 2 nulls
      ;; Size = 4 + 4 + 4 + 2 = 14 (for body "test")
      (is (= 18 (count packet))) ;; 4 (size) + 14 (payload)
      ;; First 4 bytes should be little-endian size (14)
      (is (= 14 (bit-or (bit-and (nth packet 0) 0xFF)
                        (bit-shift-left (bit-and (nth packet 1) 0xFF) 8)))))))

(deftest test-pack-packet-empty-body
  (testing "pack-packet handles empty body"
    (let [packet (protocol/pack-packet 1 0 "")]
      ;; Size = 4 + 4 + 0 + 2 = 10
      (is (= 14 (count packet))))))

(deftest test-unpack-response
  (testing "unpack-response extracts id, type, and body"
    ;; Create a valid response packet (without size prefix)
    ;; ID=1 (little-endian), Type=0 (little-endian), Body="OK", nulls
    (let [data (byte-array [1 0 0 0   ;; ID = 1
                            0 0 0 0   ;; Type = 0
                            79 75     ;; "OK"
                            0 0])     ;; Null terminators
          response (protocol/unpack-response data)]
      (is (= 1 (:id response)))
      (is (= 0 (:type response)))
      (is (= "OK" (:body response))))))

(deftest test-packet-types
  (testing "packet type constants are correct"
    (is (= 3 protocol/SERVERDATA_AUTH))
    (is (= 2 protocol/SERVERDATA_AUTH_RESPONSE))
    (is (= 2 protocol/SERVERDATA_EXECCOMMAND))
    (is (= 0 protocol/SERVERDATA_RESPONSE_VALUE))))

(deftest test-encode-body-utf8
  (testing "encode-body handles UTF-8 correctly"
    (let [encoded (protocol/encode-body "hello")]
      (is (= 5 (count encoded)))
      (is (= (seq (.getBytes "hello" "UTF-8")) (seq encoded))))))

(deftest test-decode-body-handles-encoding
  (testing "decode-body handles different encodings"
    (let [data (.getBytes "hello" "UTF-8")]
      (is (= "hello" (protocol/decode-body data))))))

;; Run tests when loaded
(clojure.test/run-tests 'ark-discord-bot.rcon.protocol-test)
