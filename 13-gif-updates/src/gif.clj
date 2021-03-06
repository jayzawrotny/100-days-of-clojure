(ns gif
  (:require [clojure.core.async :as async]
            [clojure.pprint :refer [pprint pp]])
  (:import (javax.imageio ImageIO ImageReader)
           (javax.imageio.metadata IIOMetadata)
           (javax.imageio.stream ImageInputStream)
           (java.awt Graphics)
           (java.awt.image BufferedImage)
           (java.net URL)
           (java.io File IOException)
           (java.util Collections HashMap Map)
           (org.w3c.dom NamedNodeMap Node NodeList)))

;; - [X] TODO: Return a channel that emits the reader
;; - [ ] TODO: Learn how to trigger an error
(defn read-gif-file
  "Takes a string pointing to a file path and returns a channel that
  eventually gets an ImageIO reader"
  [file-path]
  (let [reader-chan (async/chan 1)]
    (async/go
      (let [reader (.next (ImageIO/getImageReadersByFormatName "gif"))
            file (File. file-path)
            ciis (ImageIO/createImageInputStream file)]
        (if (.exists file)
          (do
            (.setInput reader ciis false)
            (async/>! reader-chan reader))
          (do
            (println (format "File %s does not exist" file-path))
            (async/close! reader-chan)))))
    reader-chan))

;; - [X] TODO: Create a new buffered image
;;             new BufferedImage(
;;               image.getWidth(),
;;               image.getHeight(),
;;               BufferedImage.TYPE_INT_ARGB
;;             )
(defn frame->image
  "Takes a frame {:index :frame :meta} and returns a new BufferedImage"
  [{:keys [index frame meta]}]
  (let [image (BufferedImage.
                (.getWidth frame)
                (.getHeight frame)
                (.TYPE_INT_ARGB BufferedImage))
        graphics (.getGraphics image)]
    (.drawImage graphics image (:x meta) (:y meta) nil)
    (image)))

(defn nodelist->seq
  "Takes a w3c nodelist and returns a sequence of nodes"
  [nodelist]
  (map #(.item nodelist %)
    (take (.getLength nodelist)
      (iterate inc 0))))

(defn node->map
  "Takes a node and returns a map {:x int :y int } from a w3c node"
  [target-attrs attrs-nodelist]
  (->> target-attrs
       (map (fn [[key prop-name]]
              [key (.getNamedItem attrs-nodelist prop-name)]))
       (filter (fn [[key attr-node]]
                (identity attr-node)))
       (reduce (fn [attrs [key attr-node]]
                (assoc attrs key (Integer/valueOf (.getNodeValue attr-node))))
        {})))

(defn -parse-meta
  "Takes imageMetadata and returns a map of {:x :y} "
  [meta]
  (->> (.getAsTree meta "javax_imageio_gif_image_1.0")
       (.getChildNodes)
       (nodelist->seq)
       (filter #(= (.getNodeName %) "ImageDescriptor"))
       (map #(.getAttributes %))
       (first)
       (node->map {:x "imageLeftPosition"
                   :y "imageTopPosition"})))

(defn get-frames
  "Takes a gif image reader and returns a channel of {:index :frame :meta} for
  each frame in a gif"
  [reader]
  (let [total-frames (.getNumImages reader true)
        frames (async/chan total-frames)]
    (async/go-loop [index 0]
      (when (< index total-frames)
        (async/>! frames {:index index
                          :frame (.read reader index)
                          :meta (-parse-meta (.getImageMetadata reader index))})
        (recur (inc index))))
    frames))

;; - [ ] TODO: Create a function that ties all the above functions together
