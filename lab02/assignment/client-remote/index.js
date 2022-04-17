const util = require("util");
const thrift = require("thrift");

const LoginClientCtor = require("./gen-nodejs/Login.js");
const SearchClientCtor = require("./gen-nodejs/Search.js");
const ReportClientCtor = require("./gen-nodejs/Reports.js");
const {
  InvalidKeyException,
  ProtocolException,
  ItemA,
  FetchResult,
  FetchStatus,
} = require("./gen-nodejs/Task_types.js");
const { exit } = require("process");

const serverUrl = "lab.d3s.mff.cuni.cz";
const serverPort = 5001;

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
 * Fetch all items from the server
 * @returns {Promise<ItemA[]>}
 */
function fetchItems() {

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
 * Create a summary from all items by aggregating their values into a single item
 * @param {ItemA[]} items items to aggregate
 * @returns {Promise<object>}
 */
function formSummary(items) {
  return new Promise(function(resolve) {
    console.log("Forming summary from", items.length, "items...");
    let aggregate = {};
    for (let item of items) {
      for (let key in item) {
        if (item.hasOwnProperty(key)) {
          if (item[key] === undefined || item[key] === null) continue;

          if (!(key in aggregate)) aggregate[key] = [];

          // scalar
          if (item[key] !== Object(item[key])) {
            let strVal = item[key].toString();
            if (!(strVal in aggregate[key]))
              aggregate[key].push(strVal);
          }
          // array
          else if (item[key] instanceof Array) {
            let strVal = item[key].join(",");
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
function sendSummary(summary) {
  return new Promise(function(resolve, reject) {
    reportClient.saveSummary(summary, function (err, res) {
      if (err) reject(err);
      else if (!res) reject(new Error("Server rejected the summary"));
      else resolve();
    });
  })
}

function handleLogout() {
  return new Promise(function(resolve, reject) {
      loginClient.logOut((err, res) => {
        if (err) reject(err);
        resolve(res);
      });
  });
}


function main() {
  // credentials
  const loginName = "novelins";
  const randomKey = 123;

  handleLogin(loginName, randomKey)
    .catch(err => {
      if (err instanceof InvalidKeyException) {
        handleLogin(loginName, err.expectedKey);
      }
      else {
        connection.end();
        throw err;
      }
    })
    .then(fetchItems)
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

if (require.main === module) {
  main();
}