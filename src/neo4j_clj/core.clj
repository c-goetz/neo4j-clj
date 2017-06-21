(ns neo4j-clj.core
  "This namespace contains the logic to connect to Neo4j instances,
  create and run queries as well as creating an in-memory database for
  testing."
  (:require [neo4j-clj.compability :refer [neo4j->clj clj->neo4j]]
            [clojure.java.io :as io])
  (:import (org.neo4j.driver.v1 Values GraphDatabase AuthTokens Transaction)
           (org.neo4j.graphdb.factory GraphDatabaseSettings$BoltConnector
                                      GraphDatabaseFactory)
           (java.net ServerSocket)
           (java.io File)))

(defn create-connection
  "Returns a connection map from an url. Uses BOLT as the only communication
  protocol."
  ([url user password]
   (let [auth (AuthTokens/basic user password)
         db   (GraphDatabase/driver url auth)]
     {:url url, :user user, :password password, :db db}))
  ([url]
   (let [db (GraphDatabase/driver url)]
     {:url url, :db db})))

(defn- get-free-port []
  (let [socket (ServerSocket. 0)
        port   (.getLocalPort socket)]
    (.close socket)
    port))

(defn- create-temp-uri
  "In-memory databases need an uri to communicate with the bolt driver.
  Therefore, we need to get a free port."
  []
  (str "localhost:" (get-free-port)))

(defn- in-memory-db
  "In order to store temporary large graphs, the embedded Neo4j database uses a
  directory and binds to an url. We use the temp directory for that."
  [url]
  (let [bolt   (GraphDatabaseSettings$BoltConnector. "0")
        temp   (System/getProperty "java.io.tmpdir")
        millis (str (System/currentTimeMillis))
        folder (File. (.getPath (io/file temp millis)))]
    (-> (GraphDatabaseFactory.)
        (.newEmbeddedDatabaseBuilder folder)
        (.setConfig (.type bolt) "BOLT")
        (.setConfig (.enabled bolt) "true")
        (.setConfig (.address bolt) url)
        (.newGraphDatabase))))

(defn create-in-memory-connection
  "To make the local db visible under the same interface/map as remote
  databases, we connect to the local url. To be able to shutdown the local db,
  we merge a destroy function into the map that can be called after testing.

  _All_ data will be wiped after shutting down the db!"
  []
  (let [url (create-temp-uri)
        db  (in-memory-db url)]
    (merge (create-connection (str "bolt://" url))
           {:destroy-fn (fn [] (.shutdown db))})))

(defn destroy-in-memory-connection [connection]
  ((:destroy-fn connection)))

(defn get-session [connection]
  (.session (:db connection)))

(defn get-transaction [session]
  (.beginTransaction session))

(defn execute
  ([sess query params]
   (neo4j->clj (.run sess query (clj->neo4j params))))
  ([sess query]
   (neo4j->clj (.run sess query))))

(defn create-query
  "Convenience function. Takes a cypher query as input, returns a function that
  takes a session (and parameter as a map, optionally) and return the query
  result as a map."
  [cypher]
  (fn
    ([sess] (execute sess cypher))
    ([sess params] (execute sess cypher params))))

(defmacro defquery "Shortcut macro to define a named query."
  [name query]
  `(def ~name (create-query ~query)))

(defmacro with-in-memory-db [db & body]
  `(let [~db (create-in-memory-connection)]
     (try
       (println "I started an in-memory instance" ~db)
       ~@body
       (finally
         (destroy-in-memory-connection ~db)))))

(def success (memfn #^Transaction success))
(def failure (memfn #^Transaction failure))

(defmacro with-db-transaction [tx session & body]
  `(with-open [~tx (.beginTransaction ~session)]
     ~@body))
