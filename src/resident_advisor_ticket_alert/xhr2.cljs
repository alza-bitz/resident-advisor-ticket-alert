(ns resident-advisor-ticket-alert.xhr2
  (:require [cljs.nodejs]))

(set! js/XMLHttpRequest (cljs.nodejs/require "xhr2"))