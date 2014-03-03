This framing.spec file covers network protocol framing.

The protocol is symmetric, where requests and responses have similar
framing.  And, a "server" can initiate an out-of-the-blue message to
the "client".

  message
  - request vs response first byte
  - messageFlags (uint8)
    - oneWay (never a response)
      vs quiet (perhaps zero or 1 errorResponse)
      vs responseExpected
      vs responsesExpected
    - fencedMessage vs lastMessage vs neither
  - opCode
  - opaqueId (uint64)
  - channelId (string)
  - header*
  - body

  header
  - covers application specific metadata about the op or chunk
  - compression
  - dataType
  - txId
  - writeConcern
  - uncompressed value size (for stats)
  - partitionId
  - checkSum

  errorResponseBody
    errorCode
    msg
    extraBody*

  opCode (uint8)
  - QUIT
  - NOOP
  - CLOSE_CHANNEL
  - VERSION (also returns capabilities)

  errorCode (uint16)
  - UNEXPECTED
  - UNKNOWN_OPCODE
  - UNSUPPORTED_OPCODE
  - TOO_MANY_CHANNELS

channel
  channelId
  channelPriority
  authedUser (default: _anonymous)
  inflightMessage*
  pausedInputProcessing
    when too many msgs
    maxChannelMsgs
    maxChannelBytes
  fenced
    waiting for fenced reply so no out-of-order replies allowed

inflightMessage
  startAtHRTime
  endAtHRTime
  nextMessage
  prevMessage
  opaqueId
  op
  requestHeader*
  requestChunk* (lazy?)
    start processing even before all request chunks are received

(need to handle "stats" or other open requests with multiple responses)

how to handle flow control?
  e.g., scan / changes-stream iteration
  but, receiver is slow
  idea: occasionally send receiver a ack-request
    receiver answers (perhaps on another channel) with ack-responses
    consider another (dedicated) channel as first channel
      might be paused with too many msgs
