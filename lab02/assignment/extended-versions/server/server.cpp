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

// ctor                     = CREATED
// CREATED  + open          = OPENED
// OPENED   + getNextItem   = ITERATED
//          + getNextBatch  = ITERATED
//          + init          = ITERATED
//          + close         = CLOSED
// ITERATED + no more items = DEPLETED
//          + close         = CLOSED
// DEPLETED + close         = CLOSED
enum State { 
    CREATED, 
    OPENED,  
    ITERATED,
    DEPLETED,
    CLOSED
};

void printItem(ItemA item) {
    // std::string fieldX;
    // std::vector<int16_t> fieldY;
    // std::optional<int32_t> fieldZ;
    std::cout << " [[[ Item A ]]]" << std::endl;
    std::cout << " X === " << item.fieldX << std::endl;
    std::cout << " Y === " << std::endl;
    for (int16_t num : item.fieldY)
        std::cout << "     - " << num << std::endl;
    std::cout << " Z === ";
    if (item.fieldZ.has_value())
        std::cout << item.fieldZ.value() << std::endl;
    else
        std::cout << "none" << std::endl;
}

void printItem(ItemB item) {
    // int16_t fieldX;
    // std::optional<std::vector<std::string> > fieldY;
    // std::set<std::string> fieldZ;
    std::cout << " [[[ Item B ]]] " << std::endl;
    std::cout << " X === " << item.fieldX << std::endl;
    std::cout << " Y === ";
    if (!item.fieldY.has_value())
        std::cout << "none" << std::endl;
    else {
        std::cout << std::endl;
        for (std::string str : item.fieldY.value())
            std::cout << "     - " << str << std::endl;
    }
    std::cout << " Z === " << std::endl;
    for (std::string str : item.fieldZ)
        std::cout << "     - " << str << std::endl;
} 

void printItem(ItemC item) {
    bool fieldX;
    std::cout << " [[[ Item C ]]] " << std::endl;
    std::cout << " X === ";
    if (item.fieldX)
        std::cout << "true" << std::endl;
    else
        std::cout << "false" << std::endl;
}

class ConnectionManager {

    Database* database;
    size_t fetchIndex;
    SummaryBuilder* builder;
    State connState;

    // new config
    std::set<std::string> allowedItemTypes;
    int32_t searchLimit;

public:

    ConnectionManager() : 
        connState(State::CREATED),
        fetchIndex(0),
        // implicit config - backward compatibility
        searchLimit(1),
        allowedItemTypes(std::set{std::string("ItemA")}) {}

    bool getNextItem(Task2::ItemA& outItem) {

        std::cout << "ConnMan getNextItem called ..." << std::endl;

        // implicit init
        if (connState == State::OPENED)
            init();

        if (connState != State::ITERATED)
            return false;

        vector<Item*> found;
        database->search(allowedItemTypes, found, fetchIndex, 1);

        // depleted, return nullptr
        if (found.size() == 0) {
            connState = State::DEPLETED;
            return false;
        }

        // update memory
        fetchIndex++;
        if (found[0]->typeName() == "ItemA") {
            const ItemA item = *((ItemA*)found[0]);
            std::cout << "...ItemA generated with X = " << item.fieldX << std::endl;
            builder->add(item);
            outItem = *reinterpret_cast<Task2::ItemA*>(found[0]);
        }
        else {
            return false;
        }

        return true;
    }

    bool getNextBatch(std::vector<Task2::Item>& outItems, int32_t count) {

        std::cout << "ConnMan getNextBatch called with " << count << " items..." << std::endl;

        // implicit init
        if (connState == State::OPENED)
            init();

        if (connState != State::ITERATED)
            return false;

        vector<Item*> found;
        database->search(allowedItemTypes, found, fetchIndex, count);

        // depleted, return nullptr
        if (found.size() == 0) {
            std::cout << "...found none." << std::endl;
            connState = State::DEPLETED;
            return false;
        }

        // possibly depleted but non-empty
        if (found.size() < count) {
            std::cout << "...found only " << found.size() << std::endl;
            connState = State::DEPLETED;
        }

        // update memory & aggregate results
        fetchIndex += count;
        for (auto it = found.begin(); it != found.end(); it++) {
            Task2::Item itemUnion;
            if ((*it)->typeName() == "ItemA") {
                const ItemA item = *((ItemA*)(*it));
                printItem(item);
                builder->add(item);
                itemUnion.__set_itemA(*reinterpret_cast<Task2::ItemA*>((*it)));
            }
            else if ((*it)->typeName() == "ItemB") {
                const ItemB item = *((ItemB*)(*it));
                printItem(item);
                builder->add(item);
                itemUnion.__set_itemB(*reinterpret_cast<Task2::ItemB*>((*it)));
            }
            else if ((*it)->typeName() == "ItemC") {
                const ItemC item = *((ItemC*)(*it));
                printItem(item);
                builder->add(item);
                itemUnion.__set_itemC(*reinterpret_cast<Task2::ItemC*>((*it)));
            }
            outItems.push_back(itemUnion);
        }

        return true;
    }

    bool getSummary(Task2::Summary& summary) {
        if (connState != State::DEPLETED)
            return false;
            
        summary = builder->get();
        return true;
    }

    bool init(std::set<std::string> allowedItemTypes, int32_t searchLimit) {
        std::cout << "ConnMan explicit init called with limit " << searchLimit << " and allowed types " << std::endl;
        for (std::string allowedType : allowedItemTypes)
            std::cout << " - " << allowedType << std::endl;

        if (connState != State::OPENED)
            return false;

        this->allowedItemTypes = allowedItemTypes;
        this->searchLimit = searchLimit;

        init();

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
        if (connState != State::OPENED && 
            connState != State::DEPLETED &&
            connState != State::ITERATED) {
            return false;
        }

        connState = State::CLOSED;
        return true;
    }

private:
    
    // implicit init - backward compatibility
    void init() {

        std::cout << "ConnMan implicit init called." << std::endl;

        std::random_device random_device;
        std::mt19937 random_engine(random_device());

        database = new Database(searchLimit, random_engine);
        builder = new SummaryBuilder();

        connState = State::ITERATED;
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
        std::cout << "LoginHandler got logout request..." << std::endl;
        if (!connMan->close()) {
            std::cout << "...protocol exception, user either not logged in or already closed connection." << std::endl;
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
    
    bool init(const std::set<std::string> & itemType, const int32_t limit) {
        return connMan->init(itemType, limit);
    }

    void fetchBatch(Task2::BatchFetchResult& _return, const int32_t count) {

        // DEBUG
        std::cout << "SearchHandler fetchBatch ..." << std::endl;

        if (rand() % 10 == 0) {
            _return.status = Task2::FetchStatus::PENDING;
            std::cout << "... pending." << std::endl;
            return;
        }

        std::vector<Task2::Item> nextItems;
        if (connMan->getNextBatch(nextItems, count)) {
            std::cout << "... sending item." << std::endl;
            _return.status = Task2::FetchStatus::ITEM;
            _return.items = nextItems;
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
        std::cout << "... ...incoming summary has content: " << std::endl;
        for (std::pair<std::string, std::set<std::string>> pair : summary) {
            std::cout << "... ... - " << pair.first << " of size " << pair.second.size() << std::endl;
            std::cout << "... ... ... with content:" << std::endl;
            for (std::string str : pair.second)
                std::cout << str << std::endl;
        }
        std::cout << "... ...expected summary has content: " << std::endl;
        for (std::pair<std::string, std::set<std::string>> pair : expectedSummary) {
            std::cout << "... ... - " << pair.first << " of size " << pair.second.size() << std::endl;
            std::cout << "... ... ... with content:" << std::endl;
            for (std::string str : pair.second)
                std::cout << str << std::endl;
        }
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