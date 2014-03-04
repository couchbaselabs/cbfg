This framing.spec file covers network protocol framing.

The protocol is asymmetric.  Although requests and responses have
similar header and body framing, there is a clear distinction between
server and client sides of a connection.  A server cannot send a
message to a client "out of the blue".  Instead, responses (if any,
and there might be more than one response message to a request) must
be due to an earlier client request.

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
    - affects which messages are processed first
    - and downstream tasks should inherit channel priority
      - example: high priority backfill or changes stream
  authedUser (default: _anonymous)
  inflightMessage*
  pausedInputProcessing
    when too many msgs
    maxChannelMsgs
    maxChannelBytes
    a channel with too many msgs
      sends back error msg so that client can retry?
      (or, alternative answer, should it just block the entire conn?)
  fenced
    waiting for fenced reply so no out-of-order replies allowed

conn
  do we need these?
    maxInflightMsgs
    maxInflightBytes
    should these send back error?  or block the entire conn?

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

what if a conn closes?
- need to have engineer internals stop any in-flight request processing.

what if a channel closes?
- need to have engineer internals stop any in-flight request processing.
- a channel close is also implicitly fenced
-- so, there will not be any new responses on that channel
   after the channel-close is processed.

should there be explicit channel open?

what if channel is immediately reopened?
- no problem, the semantics are clear with the fenced channel-close.