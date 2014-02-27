This framing.spec file covers network protocol framing.

The protocol is symmetric, where requests and responses have similar
framing.  And, a "server" can initiate an out-of-the-blue message to
the "client".

  message
  - request vs response first byte
  - messageFlags (uint8)
    - oneWay (never a response)
      vs quiet (perhaps one or more error responses)
      vs responsesExpected
    - fenced vs last
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
