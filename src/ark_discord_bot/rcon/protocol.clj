(ns ark-discord-bot.rcon.protocol
    "RCON binary protocol implementation.
   Handles packet packing/unpacking with little-endian byte order."
    (:import [java.nio ByteBuffer ByteOrder]))

;; RCON packet types
(def SERVERDATA_AUTH 3)
(def SERVERDATA_AUTH_RESPONSE 2)
(def SERVERDATA_EXECCOMMAND 2)
(def SERVERDATA_RESPONSE_VALUE 0)

(defn encode-body
  "Encode string body to UTF-8 bytes."
  [body]
  (.getBytes (str body) "UTF-8"))

(defn decode-body
  "Decode bytes to string, trying multiple encodings."
  [data]
  (String. (bytes data) "UTF-8"))

(defn- create-buffer
  "Create a little-endian ByteBuffer of given size."
  [size]
  (-> (ByteBuffer/allocate size)
      (.order ByteOrder/LITTLE_ENDIAN)))

(defn pack-packet
  "Pack RCON packet into byte array.
   Format: [size:4][id:4][type:4][body:n][null:2]"
  [request-id packet-type body]
  (let [body-bytes (encode-body body)
        body-len (count body-bytes)
        payload-size (+ 4 4 body-len 2)  ;; id + type + body + nulls
        total-size (+ 4 payload-size)     ;; size field + payload
        buffer (create-buffer total-size)]
    (-> buffer
        (.putInt payload-size)
        (.putInt request-id)
        (.putInt packet-type)
        (.put (bytes body-bytes))
        (.put (byte 0))
        (.put (byte 0)))
    (.array buffer)))

(defn read-int-le
  "Read little-endian int from 4-byte array."
  [data]
  (-> (ByteBuffer/wrap data)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.getInt)))

(defn unpack-response
  "Unpack RCON response from byte array.
   Returns map with :id, :type, :body keys."
  [data]
  (let [buffer (-> (ByteBuffer/wrap data)
                   (.order ByteOrder/LITTLE_ENDIAN))
        id (.getInt buffer)
        type-val (.getInt buffer)
        body-len (- (count data) 8 2)  ;; total - id - type - nulls
        body-bytes (byte-array body-len)]
    (.get buffer body-bytes)
    {:id id
     :type type-val
     :body (decode-body body-bytes)}))
