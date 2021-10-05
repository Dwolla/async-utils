namespace java example.thrift

struct SimpleRequest {
  1: required string id;
}

struct SimpleResponse {
  1: required string id;
}

service SimpleService {
  SimpleResponse MakeRequest(1: required SimpleRequest request)
}
