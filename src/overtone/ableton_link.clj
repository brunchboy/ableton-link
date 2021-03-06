(ns overtone.ableton-link
  (:require overtone.link-jna-path
            [clj-native.direct :refer [defclib loadlib]]
            [clj-native.structs :refer [byref]]
            [clj-native.callbacks :refer [callback]]
            [clojure.data.priority-map :refer [priority-map]]
            [clojure.spec.alpha :as s])
  (:import  [java.io Writer]))

(defclib lib-abletonlink
  (:libname "abletonlink")
  (:functions
   (-AbletonLink_ctor AbletonLink_ctor [] void*)
   (-enable-link AbletonLink_enable [void* bool] void)
   (-is-enabled AbletonLink_isEnabled [void* ] bool)
   (-get-beat AbletonLink_getBeat [void*] double)
   (-set-beat AbletonLink_setBeat [void* double] void)
   (-set-beat-force AbletonLink_setBeatForce [void* double] void)
   (-get-phase AbletonLink_getPhase [void*] double)
   (-get-bpm AbletonLink_getBpm [void*] double)
   (-set-bpm AbletonLink_setBpm [void* double] void)
   (-get-num-peers AbletonLink_getNumPeers [void*] int)
   (-get-quantum AbletonLink_getQuantum [void*] double)
   (-set-quantum AbletonLink_setQuantum [void* double] void)
   (-update AbletonLink_update [void*] void)))

(loadlib lib-abletonlink)

(defonce -AL-pointer (-AbletonLink_ctor))

(def ^:private link-running?
  (atom false))

(def ^:private link-clock-thread-atom
  (atom nil))

(def ^:private event-queue-atom
  (atom (priority-map)))

(defmacro swallow-exceptions [& body]
  `(try ~@body (catch Exception e#)))

(defn- create-clock-thread [] 
  (future
    (while @link-running?
      (-update -AL-pointer)
      (let [cur-time (-get-beat -AL-pointer)]
        (while (and (not (empty? @event-queue-atom))
                    (<= (second (peek @event-queue-atom))
                        (-get-beat -AL-pointer)))
          (let [event (-> @event-queue-atom peek first)]
            (reset! event-queue-atom (pop @event-queue-atom))
            (when-not @(:stop-atom event)
              (swallow-exceptions ((:fun event)))
              (when (:recurring event)
                (swap! event-queue-atom assoc event
                       (+ cur-time (:perioid event))))))))
      (Thread/sleep 1))))

(s/fdef enable-link :args (s/cat :bool boolean?))

(defn enable-link
  "Enable link"
  [bool]
  (if (true? bool)
    (when-not @link-running?
      (do (reset! link-running? true)
          (reset! link-clock-thread-atom (create-clock-thread))))
    (do (reset! link-running? false)
        (reset! link-clock-thread-atom nil)))
  (-enable-link -AL-pointer bool))

(defn link-enabled?
  "Returns true if link is enabled"
  []
  (-is-enabled -AL-pointer))

(defn get-beat
  "Sync(update) with link and
   return the current bpm"
  []
  (-get-beat -AL-pointer))

(s/fdef set-beat :args (s/cat :beat number?))

(defn set-beat
  "Globally set the value of the beat (number)"
  [beat]
  (-set-beat -AL-pointer beat))

(s/fdef set-beat-force :args (s/cat :beat number?))

(defn set-beat-force
  "Forcefully and globally set
   the value of the beat (number)"
  [beat]
  (-set-beat-force -AL-pointer beat))

(defn get-phase
  "Get the current phase of a bar"
  []
  (-get-phase -AL-pointer))

(defn get-bpm
  "Get the current global bpm"
  []
  (-get-bpm -AL-pointer))

(s/fdef set-bpm :args (s/cat :bpm number?))

(defn set-bpm
  "Globally change the bpm on the link"
  [bpm]
  (-set-bpm -AL-pointer bpm))

(defn get-num-peers
  "Get number of connected peers"
  []
  (-get-num-peers -AL-pointer))

(s/fdef set-quantum :args (s/cat :quantum number?))

(defn set-quantum
  "Sets the quantum, in a way like setting a
   time-signature of a bar"
  [quantum]
  (-set-quantum -AL-pointer quantum))


(defn get-quantum
  "Get the quantum of a bar, returns number,
   (return number of beats in a bar)"
  []
  (-get-quantum -AL-pointer))

(defn- append-event-to-queue [event-record time]
  (swap! event-queue-atom assoc event-record time))

(defrecord ScheduledEvent
    [fun recurring perioid stop-atom])

(defmethod print-method ScheduledEvent
  [obj ^Writer w]
  (.write w "#<Link ScheduledEvent>"))

(defn every [period fun & {:keys [initial-delay]
                           :or {initial-delay 0}}]
  (let [event (ScheduledEvent. fun true period (atom false))]
    (append-event-to-queue event 0)
    event))

(defn at [time fun & ignored]
  (let [event (ScheduledEvent. fun false 0 (atom false))]
    (append-event-to-queue event time)
    event))

(defn after [delay fun & ignored]
  (let [time (+ delay (-get-beat -AL-pointer))
        event (ScheduledEvent. fun false 0 (atom false))]
    (append-event-to-queue event time)
    event))

(defn stop [& scheduled-events]
  (when-not (empty? scheduled-events)
    (run! #(reset! (:stop-atom %) true)
          scheduled-events)))

(defn stop-all []
  (reset! event-queue-atom (priority-map)))


(comment
  (enable-link true)
  (get-beat)
  (enable-link false)
  (stop-all)
  (def fn1 #(println "f"))
  @event-queue-atom
  (def a (every 1 #'fn1))
  (stop a))
