(ns cerber.stores.authcode-test
  (:require [cerber.stores
             [authcode :refer :all]
             [client :as c]
             [user :as u]]
            [cerber.oauth2.common :refer :all]
            [midje.sweet :refer :all])
  (:import cerber.stores.authcode.AuthCode))

(defonce client (c/create-client "http://foo.com" ["http://foo.com/callback"] ["photo:read"]  nil ["moderator"] false))
(defonce user (u/create-user {:login "nioh"} "alamakota"))

(def scope "photo:read")
(def redirect "http://localhost:8080/callback")

(fact "Newly created authcode is returned with secret code filled in."
      (with-authcode-store (create-authcode-store :in-memory)
        (let [authcode (create-authcode client user scope redirect)]
          authcode => (instance-of AuthCode)
          authcode => (has-secret :code))))

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Authcode found in a store is returned with secret code filled in."
         (with-authcode-store (create-authcode-store ?store)
           (purge-authcodes)
           (let [authcode (create-authcode client user scope redirect)
                 found (find-authcode (:code authcode))]

             found => (instance-of AuthCode)
             found => (has-secret :code)))))

 ?store :in-memory :sql :redis)

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Revoked authcode is not returned from store."
         (with-authcode-store (create-authcode-store ?store)
           (purge-authcodes)
           (let [authcode (create-authcode client user scope redirect)]
             (find-authcode (:code authcode)) => (instance-of AuthCode)
             (revoke-authcode authcode)
             (find-authcode (:code authcode)) => nil))))

 ?store :in-memory :sql :redis)

(tabular
 (with-state-changes [(before :contents (.start redis))
                      (after  :contents (.stop redis))]
   (fact "Expired authcodes are removed from store."
         (against-background (default-valid-for) => -1)
         (with-authcode-store (create-authcode-store ?store)
           (purge-authcodes)
           (let [authcode (create-authcode client user scope redirect)]
             (find-authcode (:code authcode))) => nil)))

 ?store :in-memory :sql :redis)
