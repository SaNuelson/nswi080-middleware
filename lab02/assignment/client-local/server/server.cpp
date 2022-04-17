// Standard library headers
#include <memory>
#include <iostream>
#include <string>
#include <sstream>
#include <mutex>
#include <functional>

// Thrift headers
#include <thrift/protocol/TProtocol.h>
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/protocol/TMultiplexedProtocol.h>
#include <thrift/transport/TSocket.h>
#include <thrift/transport/TTransportUtils.h>
#include <thrift/server/TServer.h>
#include <thrift/server/TThreadedServer.h>
#include <thrift/processor/TMultiplexedProcessor.h>
#include <thrift/TProcessor.h>
#include <thrift/Thrift.h>

// Generated headers
#include "gen-cpp/Login.h"
#include "gen-cpp/Reports.h"
#include "gen-cpp/Search.h"
#include "gen-cpp/Task_types.h"

#include "string_conversions.hpp"
#include "summary.hpp"
#include "database.hpp"

using namespace apache::thrift;
using namespace apache::thrift::transport;
using namespace apache::thrift::protocol;
using namespace apache::thrift::server;
using namespace std;

enum State { 
    CREATED, // after ctor 
    OPENED, // after successful login
    DEPLETED, // after all items fetched
    CLOSED // after logout
};

class ConnectionManager {

    Database* database;
    size_t fetchIndex;
    SummaryBuilder* builder;
    State connState;

public:

    ConnectionManager() {
        connState = State::CREATED;
        fetchIndex = 0;

        std::random_device random_device;
        std::mt19937 random_engine(random_device());

        database = new Database(200, random_engine);
        builder = new SummaryBuilder();
    }

    bool getNextItem(Task2::ItemA& outItem) {
        if (connState != State::OPENED)
            return false;

        vector<Item*> found;
        database->search(std::set{std::string("ItemA")}, found, fetchIndex, 1);

        // depleted, return nullptr
        if (found.size() == 0) {
            connState = State::DEPLETED;
            return false;
        }

        // update memory
        fetchIndex++;
        const ItemA item = *((ItemA*)found[0]);
        builder->add(item);
    
        Task2::ItemA* itemPtr = reinterpret_cast<Task2::ItemA*>(found[0]);
        outItem = *itemPtr;
        return true;
    }

    bool getSummary(Task2::Summary& summary) {
        if (connState != State::DEPLETED)
            return false;
            
        summary = builder->get();
        return true;
    }
    
    bool open() {
        if (connState != State::CREATED) {
            return false;
        }
        
        connState = State::OPENED;
        return true;
    }

    bool close() {
        if (connState != State::OPENED && connState != State::DEPLETED) {
            return false;
        }

        connState = State::CLOSED;
        return true;
    }
};

class LoginHandler : public Task2::LoginIf {
    unsigned connectionId;
    shared_ptr<ConnectionManager> connMan;

public:
    
    LoginHandler(unsigned connectionId, shared_ptr<ConnectionManager> connMan) : connectionId(connectionId), connMan(connMan) {}

public:

    void logIn(const string& userName, const int32_t key) {

        size_t expectedKey = hash<string>{}(userName) % 100000;

        // DEBUG
        std::cout << "LoginHandler got user " << userName << " with key " << key << std::endl;
        std::cout << "...expected key " << expectedKey << std::endl;
        // DEBUG

        if (key != expectedKey) {
            Task2::InvalidKeyException badKey;
            badKey.invalidKey = key;
            badKey.expectedKey = expectedKey;
            throw badKey;
        }

        if (!connMan->open()) {
            throw Task2::ProtocolException();
        }
    }

    void logOut() {
        if (!connMan->close()) {
            throw Task2::ProtocolException();
        }
    }
};

class SearchHandler : public Task2::SearchIf {
    unsigned connectionId;
    shared_ptr<ConnectionManager> connMan;

public:

    SearchHandler(unsigned connectionId, shared_ptr<ConnectionManager> connMan) : connectionId(connectionId), connMan(connMan) {}

    void fetch(Task2::FetchResult& _return) {

        // DEBUG
        std::cout << "SearchHandler fetch ..." << std::endl;

        if (rand() % 10 == 0) {
            _return.status = Task2::FetchStatus::PENDING;
            std::cout << "... pending." << std::endl;
            return;
        }

        Task2::ItemA nextItem;
        if (connMan->getNextItem(nextItem)) {
            std::cout << "... sending item." << std::endl;
            _return.status = Task2::FetchStatus::ITEM;
            _return.item = nextItem;
        }
        else {
            std::cout << "... sending end." << std::endl;
            _return.status = Task2::FetchStatus::ENDED;
        }
    }
};

class ReportsHandler : public Task2::ReportsIf {
    unsigned connectionId;
    shared_ptr<ConnectionManager> connMan;

public:

    ReportsHandler(unsigned connectionId, shared_ptr<ConnectionManager> connMan) : connectionId(connectionId), connMan(connMan) {}

    bool saveSummary(const Task2::Summary& summary) {
        std::cout << "ReportsHandler received summary ..." << std::endl;
        Task2::Summary expectedSummary;
        
        if (!connMan->getSummary(expectedSummary)) {
            std::cout << "...cannot provide, connection is either closed or user not logged in." << std::endl;
            throw Task2::ProtocolException();
        }

        if (summary == expectedSummary) {
            std::cout << "...summary checkout out, confirming..." << std::endl;
            return true;
        }

        std::cout << "...inconsistencies found, declining ..." << std::endl;
        return false;
    }
};

class PerConnectionTaskProcessorFactory : public TProcessorFactory {
    unsigned connectionIdCounter;
    mutex lock;

public:
    PerConnectionTaskProcessorFactory(): connectionIdCounter(0) {}

    unsigned assignId() {
        lock_guard<mutex> counterGuard(lock);
        return ++connectionIdCounter;
    }

    virtual std::shared_ptr<TProcessor> getProcessor(const TConnectionInfo& connInfo) {
        unsigned connectionId = assignId();

        shared_ptr<TMultiplexedProcessor> muxProcessor(new TMultiplexedProcessor());

        shared_ptr<ConnectionManager> connManager(new ConnectionManager());

        shared_ptr<LoginHandler> login_handler(new LoginHandler(connectionId, connManager));
        shared_ptr<TProcessor> login_processor(new Task2::LoginProcessor(login_handler));
        muxProcessor->registerProcessor("Login", login_processor);

        shared_ptr<SearchHandler> search_handler(new SearchHandler(connectionId, connManager));
        shared_ptr<TProcessor> search_processor(new Task2::SearchProcessor(search_handler));
        muxProcessor->registerProcessor("Search", search_processor);

        shared_ptr<ReportsHandler> reports_handler(new ReportsHandler(connectionId, connManager));
        shared_ptr<TProcessor> reports_processor(new Task2::ReportsProcessor(reports_handler));
        muxProcessor->registerProcessor("Reports", reports_processor);

        return muxProcessor;
    }
};

int main(){
    
    try{
        // Accept connections on a TCP socket
        shared_ptr<TServerTransport> serverTransport(new TServerSocket(5000));
        // Use buffering
        shared_ptr<TTransportFactory> transportFactory(new TBufferedTransportFactory());
        // Use a binary protocol to serialize data
        shared_ptr<TProtocolFactory> protocolFactory(new TBinaryProtocolFactory());
        // Use a processor factory to create a processor per connection
        shared_ptr<TProcessorFactory> processorFactory(new PerConnectionTaskProcessorFactory());

        // Start the server
        TThreadedServer server(processorFactory, serverTransport, transportFactory, protocolFactory);
        server.serve();
    }
    catch (TException& tx) {
        cout << "ERROR: " << tx.what() << endl;
    }

}