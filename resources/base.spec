stateMechine

lvar
- vectorClock
- revisionTree
- transactionProposal

hash

range

relation
scratch: relation

boom
  {join [x y z]
   where (> x.b y.b)
   select [x.a z.a z.b]
   into [w w2 w3]}

queue

checksum

internals
- queue
-- send queue
-- recv queue
-- write queue
-- read queue
- snapshotable
-- iterator
-- storage
-- config

