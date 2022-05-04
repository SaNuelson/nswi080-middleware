Assignment 4 - Hazelcast
===

## Building & running

The assignment project should be compatible with both with classical IDE (e.g., IntelliJ, using pom.xml for Maven),
as well as buildable from CLI using provided **bash** scripts (shell doesn't work in some cases, I'd assume the relative CLASSPATH is the cause?).

Using Maven, the project can be simply built by installing Maven dependencies, building and running.

Using scripts, one can run:
```sh
bash make
bash run-member.sh <MEMBER-NAME>
```
to start up the cluster member (or multiple members).

Next:
```sh
bash run-client.sh <CLIENT-NAME>
# or instead to start with some existing testing data
bash run-client.sh <CLIENT-NAME> demo
```

The demo fills up the cluster with some random documents, adds some comments and favourites some documents.

And of course cleanup can be finally done by:
```sh
bash clean.sh
```
which should sift through the structure and get rid of all binaries.

Do note that this also cleans up all binaries made by IDEs (e.g., in `target/` subdirectory).

## Design & reasoning

### Hazel configuration

My first iteration of the design only had 2 maps:
- DocMetaMap holding document metadata including document itself 
- UserMetaMap holding user metadata

Their approximate structure was:
```yaml
DocMeta:
  document: Document
  lastViewedBy: String (clientName)
  viewCount: Integer

UserMeta:
  name: String
  lastViewed: String (documentName)
  favourites: List<String> (documentNames)
```

While that is a definitely viable solution, I later opted for a more granular version.


Currently hazelcast maps are made small and simple, one for each property:
- `DocumentCache` holds all the documents by their names
- `DocViewCounts` holds number of accesses for each document
- `DocComments` holds comments (which are serializable objects containing also some metadata about whom and when
- `LastViewedDoc` holds document name for each user
- `FavouriteDocs` holds list of document names for each user


Each version has its pros and cons.

The first approach follows better encapsulation, makes the system much more easily extensible and readable.
If the assignment didn't have fixed requirements, I would most likely opt for this version.


On the other hand, the current granular gives "more freedom". Most importantly, as all the data is shared, this granularity enables the data to be much easier synchronized and is much less prone to excessive blocking.

For example, if a single document is requested from multiple clients, the second approach enables the fetching process to be executed in a pipeline-like manner.
```
C1 fetch D1 ~ getDoc ~ incViews ~ setLastViewed
C2 fetch D1          ~ getDoc   ~ incViews      ~ setLastViewed
C3 fetch D1                     ~ getDoc        ~ incViews      ~ setLastViewed
```
Whereas the object-oriented version needs to lock document for both of the first operations. This would pose problem the more properties are added to each object, especially in case of simple operations (e.g., incrementing views counter requires locking the whle document structure, which is 'wasteful')


### Hazel communication

All communication with clusters is exclusively wrapped in executor tasks and processors.

While this might seem like an overkill (and in some cases most likely is), it (arguably) makes for a bit cleaner code somewhat.

More importantly, it ensures synchronization to avoid race conditions and/or deadlocks.

All getters and setters are therefore wrapped in processors.

The only exception to that is generating documents, which is wrapped in an executor task, as it shouldn't clutter the partition threads.

Additional checks are made after processors are finished. These mostly should not occur with exception of some edge cases (e.g., trying to manipulate 
last document by adding it to favorites or trying to comment doesn't make the program go belly up).
