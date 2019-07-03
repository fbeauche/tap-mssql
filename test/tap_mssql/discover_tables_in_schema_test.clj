(ns tap-mssql.discover-tables-in-schema-test
  (:require [clojure.test :refer [is deftest use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as string]
            [tap-mssql.core :refer :all]
            [tap-mssql.test-utils :refer [with-out-and-err-to-dev-null
                                          test-db-config]]))
(defn get-destroy-database-command
  [database]
  (format "DROP DATABASE %s" (:table_cat database)))

(defn maybe-destroy-test-db
  []
  (let [destroy-database-commands (->> (get-databases test-db-config)
                                       (filter non-system-database?)
                                       (map get-destroy-database-command))]
    (let [db-spec (config->conn-map test-db-config)]
      (jdbc/db-do-commands db-spec destroy-database-commands))))


(defn create-test-db
  []
  (let [db-spec (config->conn-map test-db-config)]
    (jdbc/db-do-commands db-spec ["CREATE DATABASE database_with_schema"])
    (jdbc/db-do-commands (assoc db-spec :dbname "database_with_schema") ["CREATE SCHEMA schema_with_table"])
    (jdbc/db-do-commands (assoc db-spec :dbname "database_with_schema") ["CREATE TABLE schema_with_table.data_table (id uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID(), value int)"])
    (jdbc/db-do-commands (assoc db-spec :dbname "database_with_schema")
                         [(jdbc/create-table-ddl
                           "data_table_without_schema"
                           [[:id "uniqueidentifier NOT NULL PRIMARY KEY DEFAULT NEWID()"]
                            [:value "int"]
                            [:deselected_value "int"]])])
    ))

(defn test-db-fixture [f]
  (with-out-and-err-to-dev-null
    (maybe-destroy-test-db)
    (create-test-db)
    (f)))

(use-fixtures :each test-db-fixture)

(deftest ^:integration verify-populated-catalog-with-schema
  (is (let [stream-names (set (map #(get % "stream")
                                   (vals ((discover-catalog test-db-config) "streams"))))]
        (= stream-names #{"data_table" "data_table_without_schema"})))
  (is (let [tap-stream-ids (set (map #(get % "tap_stream_id")
                                     (vals ((discover-catalog test-db-config) "streams"))))]
        (= tap-stream-ids #{"database_with_schema-schema_with_table-data_table"
                            "database_with_schema-dbo-data_table_without_schema"})))
  (is (=
       (get-in (discover-catalog test-db-config) ["streams"
                                                  "database_with_schema-schema_with_table-data_table"
                                                  "metadata"
                                                  "schema-name"])
       "schema_with_table"))
  (is (=
       (get-in (discover-catalog test-db-config) ["streams"
                                                  "database_with_schema-dbo-data_table_without_schema"
                                                  "metadata"
                                                  "schema-name"])
       "dbo"))
  )