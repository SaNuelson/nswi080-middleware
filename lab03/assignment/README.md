Assignment 3 - JMS
===

1. Create goods, sent as Offers topic message

This part was already fully functional in provided code. I modified it slightly:
- generated goods have names prefixed with client prefix, where the prefix is constructed as
  initials of its name (so "Client Alpha" will have items prefixed with "CA-")

2. Present goods and prices, update when message arrives

This again works out of the box. When items are e.g. bought and the client manually republishes
its stock, other clients don't have these items in offers anymore.

3. Implement buying

This was done using provided TODOs for the most part. The current version logs something like (in multiple CLIs):
```
BUYER  < b
BUYER  > Enter seller name:
BUYER  < Client Beta
BUYER  > Enter goods name:
BUYER  < CB-JPOM
BUYER  > Sending a reservation request to Client Gamma.
BUYER  > Seller replies the requested item is reserved.
BUYER  > Sending $893 to account 1000000
SERVER > Transferring $893 from account 1000001 to account 1000000
SELLER > Received $893 from Client Gamma
BUYER  > Buy order successful.
```

4. Consider weaknesses, extend

- Keep proper account balances

Balances are set to fixed amount (currently $1000) when clients connect to the bank.

- Support for account balance queries

This is done by manually setting `.setJMSReplyTo` to bypass async receiver for the sake of simplicity.

- More robust sell/buy

In case of insufficient balance, transfer is declined and seller is notified.

Seller then notifies the buyer that item is released instead of bought.

Buyer can also buy items for lower than their asking price. This is done via the added
'h' option (standing for haggle), which buys for half the price.

The seller is notified of the received money and if its lower than the asking price, it
notifies buyer of failed transaction and asks bank to refund the money.

This is done with an added property that ensures the refund is silent and the buyer is not
notified, to avoid infinite loop or crashes.