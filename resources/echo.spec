SERVER_HOST
SERVER_PORT

client:
  onStart:
    conn = connect()
  onInput(s):
    conn.sendMsg({opaque: opaqueGen(), msg: s})
  onRecvMsg(conn, s):

(defn echo-server [port]
  (letfn
      [(session-handler [in out]
              (loop []
                  (->>
                      (recv-msg in)
                      (send-msg out))
                  (recur)))]
      (create-msg-server port session-handler)))

