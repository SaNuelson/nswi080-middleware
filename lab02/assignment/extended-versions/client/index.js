const util = require("util");
const thrift = require("thrift");

const LoginClientCtor = require("./gen-nodejs/Login.js");
const SearchClientCtor = require("./gen-nodejs/Search.js");
const ReportClientCtor = require("./gen-nodejs/Reports.js");
const {
  InvalidKeyException,
  ProtocolException,
  ItemA,
  ItemB,
  ItemC,
  Item,
  FetchResult,
  FetchStatus,
  BatchFetchResult
} = require("./gen-nodejs/Task_types.js");
const { exit } = require("process");

const serverUrl = "localhost";
const serverPort = 5000;

const connection = thrift.createConnection(serverUrl, serverPort, {
  transport: thrift.TBufferedTransport,
  protocol: thrift.TBinaryProtocol,
});

const multiplexer = new thrift.Multiplexer();
const loginClient = multiplexer.createClient(
  "Login",
  LoginClientCtor,
  connection
);
const searchClient = multiplexer.createClient(
  "Search",
  SearchClientCtor,
  connection
);
const reportClient = multiplexer.createClient(
  "Reports",
  ReportClientCtor,
  connection
);

/**
 * Try to login using loginClient.
 * @param {string} loginName Login to use
 * @param {number} loginKey Key to use
 * @returns {Promise}
 */
async function handleLogin(loginName, loginKey) {
  return new Promise(function(resolve, reject) {
    loginClient.logIn(loginName, loginKey, function (err, res) {
      if (err) reject(err);
      else resolve(res);
    });
  })
}

/**
 * Initialize configuration for allowed types
 * @param {string[]} acceptedTypes 
 * @param {number} searchLimit
 * @returns {Promise}
 */
async function initServer(acceptedTypes = ["ItemA", "ItemB", "ItemC"], searchLimit = 50) {
  return new Promise(function (resolve, reject) {
    searchClient.init(acceptedTypes, searchLimit, function(err, res) {
      if (err) reject(err);
      else resolve(res);
    })
  })
}

/**
 * Fetch all items from the server
 * @returns {Promise<ItemA[]>}
 */
 async function fetchItems() {

  function fetchItemsInternal(buffer, resolve, reject) {

    console.log("Currently fetched", buffer.length, "items. Fetching next...");

    searchClient.fetch(function (err, res) {
    if (err) {
      throw err;
    } else {
      switch (res.status) {
        case FetchStatus.ENDED:
          console.log("... got ENDED response, fetching finished.");
          resolve(buffer);
          break;

        case FetchStatus.PENDING:
          console.log("... received PENDING response, going to sleep...");
          setTimeout(() => fetchItemsInternal(buffer, resolve, reject), 100);
          break;

        case FetchStatus.ITEM:
          console.log("... received ITEMS response, yielding item: ", res.item);
          buffer.push(res.item);
          fetchItemsInternal(buffer, resolve, reject);
          break;

        default:
          reject(new Error(
            "fetchItems got unexpected response status " + res.status
          ));
      }
    }
  });
}

  return new Promise(function(resolve, reject) {
    fetchItemsInternal([], resolve, reject);
  });
}

/**
 * Fetch all items from the server using batches (new functionality)
 * @returns {Promise<Item[]>}
 */
async function fetchBatchItems(batchSize = 10) {

  function fetchBatchItemsInternal(batchSize, buffer, resolve, reject) {

    console.log("Currently fetched", buffer.length, "items. Fetching next...");

    searchClient.fetchBatch(batchSize, function (err, /** @type {BatchFetchResult} */ res) {
    if (err) {
      throw err;
    } else {
      switch (res.status) {
        case FetchStatus.ENDED:
          console.log("... got ENDED response, fetching finished.");
          resolve(buffer);
          break;

        case FetchStatus.PENDING:
          console.log("... received PENDING response, going to sleep...");
          setTimeout(() => fetchBatchItemsInternal(batchSize, buffer, resolve, reject), 100);
          break;

        case FetchStatus.ITEM:
          console.log("... received ITEMS response, yielding item batch: ", res.items);

          /** @type {Item[]} */
          let batchItems = res.items;
          let deunionedItems = batchItems.map(item => {
            let isItemA = item.itemA !== null;
            let isItemB = item.itemB !== null;
            let isItemC = item.itemC !== null;
            
            if ((isItemA + isItemB + isItemC) !== 1)
              return null;
            
            if (isItemA) return item.itemA;
            if (isItemB) return item.itemB;
            if (isItemC) return item.itemC;
          })

          buffer.push(...deunionedItems);
          fetchBatchItemsInternal(batchSize, buffer, resolve, reject);
          break;

        default:
          reject(new Error(
            "fetchItems got unexpected response status " + res.status
          ));
      }
    }
  });
}

  return new Promise(function(resolve, reject) {
    fetchBatchItemsInternal(batchSize, [], resolve, reject);
  });
}

/**
 * Create a summary from all items by aggregating their values into a single item
 * @param {ItemA[]} items items to aggregate
 * @returns {Promise<object>}
 */
async function formSummary(items) {
  return new Promise(function(resolve) {
    console.log("Forming summary from", items.length, "items...");
    let aggregate = {};
    for (let item of items) {
      for (let key in item) {
        if (item.hasOwnProperty(key)) {

          // empty items properties are skipped
          if (item[key] === undefined || item[key] === null) continue;

          if (!(key in aggregate)) aggregate[key] = [];

          // scalar values are stringified
          if (item[key] !== Object(item[key])) {
            let strVal = item[key].toString();
            console.log("Casting scalar of key",key,"from",item[key],"to",strVal);
            if (!(strVal in aggregate[key]))
              aggregate[key].push(strVal);
          }

          // array values are made into comma-separated strings
          else if (item[key] instanceof Array) {
            let strVal = item[key].join(",");
            console.log("Casting scalar of key",key,"from",item[key],"to",strVal);
            if (!(strVal in aggregate[key]))
              aggregate[key].push(strVal);
          }
        }
      }
    }
    console.log("Ended up with following summary:", aggregate);
    resolve(aggregate);
  });
}

/**
 * Send a summary to the server, fail if server rejects it
 * @param {Map.<string, Set.<string>} summary item to send as a summary
 * @returns {Promise}
 */
async function sendSummary(summary) {
  return new Promise(function(resolve, reject) {
    reportClient.saveSummary(summary, function (err, res) {
      if (err) reject(err);
      else if (!res) reject(new Error("Server rejected the summary"));
      else resolve();
    });
  })
}

async function handleLogout() {
  return new Promise(function(resolve, reject) {
      loginClient.logOut((err, res) => {
        if (err) reject(err);
        resolve(res);
      });
  });
}


async function main(searchLimit, batchSize, allowedTypes) {
  // credentials
  const loginName = "novelins";
  const randomKey = 123;

  await handleLogin(loginName, randomKey)
    .catch(err => {
      if (err instanceof InvalidKeyException) {
        handleLogin(loginName, err.expectedKey);
      }
      else {
        connection.end();
        throw err;
      }
    })
    .then(() => {
      if (allowedTypes.length === 1 && allowedTypes[0] === "ItemA") {
        console.log("Requested default allowed types, running original fetch with only type ItemA.");
        return fetchItems();
      }
      else {
        console.log("Requested multiple allowed types, running batch fetch with types", allowedTypes);
        return initServer(allowedTypes, searchLimit)
          .then(() => fetchBatchItems(batchSize));
      }
    })
    .then(formSummary)
    .then(sendSummary)
    .then(handleLogout)
    .catch(err => {
      connection.end();
      throw err;
    })
    .then(() => {
      console.log("Task completed, closing connection.");
      connection.end()
    });
}

const helpFlags = ["-h", "--h", "-help", "--help"];
const knownTypes = ["ItemA", "ItemB", "ItemC"];

if (require.main === module) {
  let searchLimit = -1;
  let batchSize = -1;
  let allowedTypes = [];

  let args = process.argv;
  console.log(args);
  if (args.length === 0 && helpFlags.includes(args[0])) {
    console.log("node index.js [searchLimit [batchSize allowedType1 [allowedType2 [...]]]]");
  }

  if (args.length > 2) {
    if (args[2] <= 0) {
      console.error("searchLimit needs to be a positive number");
      exit(1);
    }
    searchLimit = +args[2];
  }

  if (args.length > 1) {
    if (args[3] <= 0 || args[3] >= searchLimit) {
      console.error("batchSize needs to be a positive number less that searchLimit");
      exit(1);
    }
    batchSize = +args[3];
  }

  for (let i = 4; i < args.length; i++) {
    if (!knownTypes.includes(args[i])) {
      console.error("expected allowed type("+knownTypes+"), got "+args[i]+" instead.");
      exit(1);
    }
    allowedTypes.push(args[i]);
  }

  if (allowedTypes.length === 0)
    allowedTypes = ["ItemA"];

  main(searchLimit, batchSize, allowedTypes);
}