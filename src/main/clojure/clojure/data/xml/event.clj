;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.data.xml.event
  "Data type for xml pull events"
  {:author "Herwig Hochleitner"}
  (:require [clojure.data.xml.protocols :as p :refer
             [Event EventGeneration gen-event next-events xml-str]]
            [clojure.data.xml.name :refer [separate-xmlns]]
            [clojure.data.xml.node :as node :refer [element* cdata xml-comment]]
            [clojure.data.xml.impl :refer [extend-protocol-fns compile-if]]
            [clojure.data.xml.pu-map :as pu]
            [clojure.data.xml.core :refer [code-gen unwrap-reduced]]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.data.xml.core :as core])
  (:import (clojure.data.xml.node Element CData Comment)
           (clojure.lang Sequential IPersistentMap Keyword)
           (java.net URI URL)
           (java.util Date)
           (javax.xml.namespace QName)))

(definline element-nss* [element]
  `(get (meta ~element) :clojure.data.xml/nss pu/EMPTY))

(defn element-nss
  "Get xmlns environment from element"
  [{:keys [attrs] :as element}]
  (separate-xmlns
   attrs #(pu/merge-prefix-map (element-nss* element) %2)))

(def push-methods
  '((start-element-event tag attrs nss location-info)
    (end-element-event)
    (empty-element-event tag attrs nss location-info)
    (chars-event string)
    (c-data-event string)
    (comment-event string)
    (q-name-event qname)
    (error-event error)))

(def type-name
  (core/kv-from-coll
   (core/juxt-xf first
                 #(-> % first str (str/split #"-")
                      (->> (map str/capitalize))
                      str/join symbol))
   push-methods))

(defn constructor-name [method]
  (symbol "clojure.data.xml.event"
          (str "->" (type-name method))))

(defn protocol-name [method]
  (symbol "clojure.data.xml.protocols"
          (str method)))

(code-gen
 [_ push-events] [push-handler state]
 `(do
    ~@(eduction
       (map (fn [[method & args]]
              `(defrecord ~(type-name method) [~@args]
                 Event
                 (~push-events [~_ ~push-handler ~state]
                  (~(protocol-name method) ~push-handler ~state ~@args)))))
       push-methods)))

(let [push-string (fn [string push-handler state]
                    (p/chars-event push-handler state (xml-str string)))
      push-qname (fn [qname push-handler state]
                   (p/q-name-event push-handler state qname))]
  (extend-protocol-fns
   Event
   (String Boolean Number (Class/forName "[B") Date URI URL nil)
   {:push-events push-string}
   (Keyword QName)
   {:push-events push-qname}
   IPersistentMap
   {:push-events node/push-element}
   Sequential
   {:push-events node/push-content})
  (compile-if
   (Class/forName "java.time.Instant")
   (extend java.time.Instant
     Event
     {:push-events push-string})
   nil))

;; Event Generation for stuff to show up in generated xml

(let [second-arg #(do %2)
      elem-event-generation
      {:gen-event (fn elem-gen-event [{:keys [tag attrs content] :as element}]
                    (separate-xmlns
                     attrs #((if (seq content)
                               ->StartElementEvent ->EmptyElementEvent)
                             tag %1 (pu/merge-prefix-map (element-nss* element) %2) nil)))
       :next-events (fn elem-next-events [{:keys [tag content]} next-items]
                      (if (seq content)
                        (list* content end-element-event next-items)
                        next-items))}
      string-event-generation {:gen-event (comp ->CharsEvent #'xml-str)
                               :next-events second-arg}
      qname-event-generation {:gen-event ->QNameEvent
                              :next-events second-arg}]
  (extend-protocol-fns
   EventGeneration
   (StartElementEvent EmptyElementEvent EndElementEvent CharsEvent CDataEvent CommentEvent)
   {:gen-event identity
    :next-events second-arg}
   (String Boolean Number (Class/forName "[B") Date URI URL nil)
   string-event-generation
   (Keyword QName) qname-event-generation
   CData
   {:gen-event (comp ->CDataEvent :content)
    :next-events second-arg}
   Comment
   {:gen-event (comp ->CommentEvent :content)
    :next-events second-arg}
   (IPersistentMap Element) elem-event-generation)
  (compile-if
   (Class/forName "java.time.Instant")
   (extend java.time.Instant
     EventGeneration
     string-event-generation)
   nil))

(extend-protocol EventGeneration
  Sequential
  (gen-event   [coll]
    (gen-event (first coll)))
  (next-events [coll next-items]
    (if-let [r (seq (rest coll))]
      (cons (next-events (first coll) r) next-items)
      (next-events (first coll) next-items))))

;; Node Generation for events

(defn event-element
  ([event contents]
   (when (or (instance? StartElementEvent event)
             (instance? EmptyElementEvent event))
     (event-element (:tag event)
                    (:attrs event)
                    (:nss event)
                    (:location-info event)
                    contents)))
  ([tag attrs nss location-info contents]
   (element* tag attrs contents
             (if location-info
               {:clojure.data.xml/location-info location-info
                :clojure.data.xml/nss nss}
               {:clojure.data.xml/nss nss}))))

(defn event-node [event]
  (cond
    (instance? CharsEvent event) (:string event)
    (instance? CDataEvent event) (cdata (:string event))
    (instance? CommentEvent event) (xml-comment (:string event))
    :else (throw (ex-info "Illegal argument, not an event object" {:event event}))))

(defn event-exit? [event]
  (instance? EndElementEvent event))
