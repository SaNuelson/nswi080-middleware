all: gen-cpp/Task.cpp server

# Generate source files from Thrift IDL
gen-cpp/Task.cpp: Task.thrift
	thrift --gen cpp Task.thrift

# Compile server from main source and generated sources
server: server.cpp gen-cpp/Login.cpp gen-cpp/Reports.cpp gen-cpp/Search.cpp gen-cpp/Task_types.cpp
	g++ --std=c++17 -Igen-cpp -o server server.cpp gen-cpp/Login.cpp gen-cpp/Reports.cpp gen-cpp/Search.cpp gen-cpp/Task_types.cpp -lthrift -I$(HOME)/thrift/include -L$(HOME)/thrift/lib

clean:
	rm -f server
	rm -rf gen-cpp


.PHONY: all clean