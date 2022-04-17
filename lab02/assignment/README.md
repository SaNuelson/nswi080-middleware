Thrift
===

## 1. Implement the Client

Solution written in Node.js can be found in `client-remote/`.

It can be run same as example:
```cmd
> npm run generate
> npm i
> node index.js
```

> Note: the PATH has to be extended same as in example:

```
export PATH="$PATH:$HOME/thrift/bin"
export LD_LIBRARY_PATH="$HOME/thrift/lib"
```

This client attached to the remote server and should complete successfully

## 2. Implement the Server

Solution can be gound in `client-local/client/` for client, `client-local/server/` for server.

Client can be built and started identically to previous task.

Server can be started simply by running:
```cmd
> make all
> ./server
```

The client here attaches to the localhost server and should complete successfully.

## 3. Protocol update

The solution can be found in `extended-versions/` and its subfolders `client/` and `server/`.

Both can be built and run by running the same commands as in previous tasks.

The client additionally takes CLI arguments in format:
```
> node index.js [searchLimit batchSize allowedItem1 [allowedItem2 [...]]]
```

In case these are omitted, the client fallbacks to the non-extended version.

The modified Thrift file is identical in both. It is modified in the following way:
- it contains definitions for ItemB and ItemC respectively.
- it contains a `union` which gathers all of the item types in a single structure
- it contains `BatchFetchResult`, a modified version of original `FetchResult`, which contains a batch of `Item`s
- Search service is augmented with `init` and `fetchBatch` methods according to the task assignment

Here an issue arises which I'm trying to resolve for over a week now:

This version of client fails to create a summary which will get approved by the server.

Using the extensive logging present both in client and server, I was able to pinpoint that this issue arises from the fact,
that `ItemB`'s `fieldY` which should be `optional<list<string>>` fails to get passed to the client.

More specifically, the client receives `null` in this case and I'm not sure as to why.

## Final remarks

The extended versions of both server and client are compatible with older versions.

The new server is compatible with old client "out of the box".

The old server is compatible with new client once the allowed types are disabled in the new client (by omitting CLI args).