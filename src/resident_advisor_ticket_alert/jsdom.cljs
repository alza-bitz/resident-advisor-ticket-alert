(ns resident-advisor-ticket-alert.jsdom
  (:require [cljs.nodejs]))

(set! (. goog.global -Node) goog.dom.NodeType)

(set! (. goog.global -NodeList) goog.dom.NodeList)

(def jsdom (cljs.nodejs/require "jsdom"))

(set! (. goog.global -document) (.-document (.-window (jsdom.JSDOM.))))