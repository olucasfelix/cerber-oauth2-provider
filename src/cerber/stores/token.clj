(ns cerber.stores.token
  (:require [mount.core :refer [defstate]]
            [cerber
             [db :as db]
             [config :refer [app-config]]
             [store :refer :all]]
            [failjure.core :as f]
            [cerber.stores.user :as user]
            [cerber.error :as error])
  (:import [cerber.store MemoryStore RedisStore]))

(defn default-valid-for []
  (-> app-config :cerber :tokens :valid-for))

(defrecord Token [client-id user-id login scope secret created-at expires-at])

(defn ->map [result]
  (when-let [{:keys [client_id user_id login scope secret created_at expires_at]} result]
    {:client-id client_id
     :user-id user_id
     :login login
     :scope scope
     :secret secret
     :created-at created_at
     :expires-at expires_at}))

(defrecord SqlTokenStore []
  Store
  (fetch-one [this [client-id tag secret]]
    (->map (first (db/find-tokens-by-secret {:client-id client-id :secret secret :tag tag}))))
  (fetch-all [this [client-id tag secret login]]
    (map ->map (if secret
                    (db/find-tokens-by-secret {:client-id client-id :secret secret :tag tag})
                    (if client-id
                      (db/find-tokens-by-login-and-client {:client-id client-id :login login :tag tag})
                      (db/find-tokens-by-login {:login login :tag tag})))))
  (revoke-one! [this [client-id tag secret]]
    (db/delete-token-by-secret {:client-id client-id :secret secret}))
  (revoke-all! [this [client-id tag secret login]]
    (map ->Token (if login
                   (db/delete-tokens-by-login  {:client-id client-id :login login})
                   (db/delete-tokens-by-client {:client-id client-id}))))
  (store! [this k token]
    (when (= 1 (db/insert-token token)) token))
  (purge! [this]
    (db/clear-tokens)))

(defmulti create-token-store identity)

(defstate ^:dynamic *token-store*
  :start (create-token-store (-> app-config :cerber :tokens :store)))

(defmethod create-token-store :in-memory [_]
  (MemoryStore. "tokens" (atom {})))

(defmethod create-token-store :redis [_]
  (RedisStore. "tokens" (-> app-config :cerber :redis-spec)))

(defmethod create-token-store :sql [_]
  (SqlTokenStore.))

(defmacro with-token-store
  "Changes default binding to default token store."
  [store & body]
  `(binding [*token-store* ~store] ~@body))

(defn create-token
  "Creates new token"
  [client user scope & [opts]]
  (let [{:keys [ttl tag] :or {tag :access ttl (default-valid-for)}} opts
        token (-> {:client-id (:id client)
                   :user-id (:id user)
                   :login (:login user)
                   :secret (generate-secret)
                   :scope scope
                   :expires-at (when (= tag :access) (now-plus-seconds ttl))
                   :created-at (java.util.Date.)
                   :tag (name tag)})]

    (if-let [result (store! *token-store* [:client-id :tag :secret :login] token)]
      (map->Token result)
      (error/internal-error "Cannot create token"))))

;; revocation

(defn revoke-by-pattern [key] (revoke-all! *token-store* key) nil)
(defn revoke-by-key [key] (revoke-one! *token-store* key) nil)

(defn revoke-access-token
  [token]
  (when-let [client-id (:client-id token)]
    (when-let [login (:login token)]
      (revoke-by-key [client-id "access" (:secret token) login]))))

;; retrieval

(defn find-by-pattern
  "Finds token by vectorized pattern key.
  Each nil element of key will be replaced with wildcard specific for underlaying store implementation."

  [key]
  (when-let [tokens (fetch-all *token-store* key)]
    (map (fn [t] (map->Token t)) tokens)))

(defn find-by-key
  "Finds token by vectorized exact key.
  Each element of key is used to compose query depending on underlaying store implementation."

  [key]
  (when-let [result (fetch-one *token-store* key)]
    (map->Token result)))

(defn find-access-token
  "Finds access token issued for given client-user pair with particular auto-generated secret code."

  [client-id secret login]
  (find-by-key [client-id "access" secret login]))

(defn find-refresh-token
  "Finds refresh token issued for given client-user pair with particular auto-generated secret code."

  [client-id secret login]
  (first (find-by-pattern [client-id "refresh" secret login])))

(defn purge-tokens
  "Removes token from store. Used for tests only."
  []
  (purge! *token-store*))

;; generation

(defn generate-access-token
  "Generates access-token for given client-user pair within provided scope.
  Additional options (type, refresh?) may adjust token type (Bearer by default)
  and decide whether to generate refresh-token as well or not (no refresh-tokens by default).

  Asking again for refresh-token generation (through :refresh? true option) reuses prevously
  generated refresh-token for given client/user pair."

  [client user scope & [opts]]
  (let [access-token (create-token client user scope)
        {:keys [secret created-at expires-at login]} access-token
        {:keys [type refresh?] :or {type "Bearer"}} opts]

    (if (f/failed? access-token)
      access-token
      (let [refresh-token (and refresh?
                               (or (find-refresh-token (:id client) nil (:login user))
                                   (create-token client user scope {:tag :refresh})))]

        (-> {:access_token secret
             :token_type type
             :created_at created-at
             :expires_in (/ (- (.getTime expires-at)
                               (.getTime created-at)) 1000)}
            (cond-> scope
              (assoc :scope scope))
            (cond-> (and refresh-token (not (f/failed? refresh-token)))
              (assoc :refresh_token (:secret refresh-token))))))))

(defn refresh-access-token
  "Refreshes access and refresh-tokens using provided refresh-token.
  Already existing access- and refresh-tokens generated for given client-user pair get removed."

  [refresh-token]
  (let [{:keys [client-id user-id login scope]} refresh-token]
    (revoke-by-pattern [client-id nil nil login])
    (generate-access-token {:id client-id}
                           {:id user-id :login login}
                           scope
                           {:refresh? true})))
