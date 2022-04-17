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

class LoginHandler : public Task2::LoginIf {
    unsigned connectionId;

public:
    
    LoginHandler(unsigned connectionId) : connectionId(connectionId) {}

public:

    void logIn(const string& userName, const int32_t key) {
        size_t expectedKey = hash<string>{}(userName);
        if (key != expectedKey) {
            Task2::InvalidKeyException badKey;
            badKey.invalidKey = key;
            badKey.expectedKey = expectedKey;
            throw badKey;
        }
    }

    void logOut() {}
};

class SearchHandler : public Task2::SearchIf {
    unsigned connectionId;
    std::vector<Task2::ItemA*> items;
    int index;

public:

    SearchHandler(unsigned connectionId, std::vector<Task2::ItemA*> items) : connectionId(connectionId), items(items), index(0) {}

    void fetch(Task2::FetchResult& _return) {
        if (rand() % 10 == 0) {
            _return.status = Task2::FetchStatus::PENDING;
        }
        else if (index < items.size()) {
            _return.status = Task2::FetchStatus::ITEM;
            _return.item = *items.at(index);
            index++;
        }
        else {
            _return.status = Task2::FetchStatus::ENDED;
        }
    }
};

class ReportsHandler : public Task2::ReportsIf {
    unsigned connectionId;
    Task2::Summary summary;

public:

    ReportsHandler(unsigned connectionId, Task2::Summary summary) : connectionId(connectionId), summary(summary) {}

    bool saveSummary(const Task2::Summary& summary) {
        if (summary == this->summary)
            return true;
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

        std::random_device random_device;
        std::mt19937 random_engine(random_device());
        Database database(200, random_engine);

        int index = 0;
        vector<Item*> found;
        vector<Task2::ItemA*> items;
        database.search(std::set{std::string("ItemA")}, found, index, 50);

        SummaryBuilder builder;
        for (int i = 0; i < found.size(); i++) {
            Task2::ItemA* itemPtr = reinterpret_cast<Task2::ItemA*>(found[i]);
            const ItemA item = *((ItemA*)found[i]);

            builder.add(item);
            items.push_back(itemPtr);
        }
        Task2::Summary summary = builder.get();

        shared_ptr<LoginHandler> login_handler(new LoginHandler(connectionId));
        shared_ptr<TProcessor> login_processor(new Task2::LoginProcessor(login_handler));
        muxProcessor->registerProcessor("Login", login_processor);

        shared_ptr<SearchHandler> search_handler(new SearchHandler(connectionId, items));
        shared_ptr<TProcessor> search_processor(new Task2::SearchProcessor(search_handler));
        muxProcessor->registerProcessor("Search", search_processor);

        shared_ptr<ReportsHandler> reports_handler(new ReportsHandler(connectionId, summary));
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