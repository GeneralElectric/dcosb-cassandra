## Building

This Service broker is packaged and distributed as a docker image. During the build, sensitive information from the environment is written
in to the image, such as passwords and location of private keys.

Default passwords and key stores found in this repository ( * ) should _not_ be used in any
but local dev environments or sandboxes.

### Environment variables for the docker build

| ENVVAR                        | Description                                           | Default value           |
| ----------------------------- | ----------------------------------------------------- | ----------------------- |
| DCOS_HOST                     | DC/OS API host                                        | mesos.master |
| DCOS_PORT                     | DC/OS API port                                        | 443 |
| DCOS_PRINCIPAL                | DC/OS principal used to carry out API operations      | cassandra-broker |
| * DCOS_PRINCIPAL_PK_ALIAS     | DC/OS principal's private key's alias in the store    | dcos-principal |
| * DCOS_PRINCIPAL_PK_PSW       | DC/OS principal's private key's password              | `8BWTL0ie` |
| * OSB_API_USR                 | User name used to secure the OSB API                  | apiuser |
| * OSB_API_PSW                 | Password used to secure the OSB API                   | `2cd8a28b6e8b1547f58861f77dba1f72f38a54b1a97a9faaad34424b57a96aad` ( hash of `YYz3aN-kmw` ) |
| BROKER_LISTEN_ADDRESS         | Address the OSB API will listen on                    | 0.0.0.0 |
| BROKER_LISTEN_PORT            | Port the OSB API will listen in                       | 8080 |
| * BROKER_KEY_STORE            | Path of the key store file to add to docker image     | `src/main/resources/cassandra.p12` |
| * BROKER_KEY_STORE_TYPE       | Keystore type for OSB API over TLS & DC/OS Connection | local |
| * BROKER_KEY_STORE_URI        | Keystore URI for OSB API over TLS & DC/OS Connection  | pkcs12:cassandra.p12 |
| * BROKER_KEY_STORE_PSW        | Password of the above keystore                        | `8BWTL0ie` |
| BROKER_TRUST_STORE            | Path to keystore with DC/OS cluster cert              | _empty_ = use file from service-broker jar |
| BROKER_TRUST_STORE_TYPE       | Type of the trust store                               | PKCS12 |
| BROKER_TRUST_STORE_PSW        | Password on the above trust store                     | `swim87'bleat` |
| BROKER_TRUST_STORE_CLASSPATH  | If the trust store is on the classpath or not         | true |
| BROKER_TLS_PK_CERT_STORE      |                                                       | broker |
| BROKER_TLS_PK_CERT_ALIAS      | Alias of the private key & cert used for TLS          | dcosb.marathon.mesos |
| BROKER_TLS_PK_PSW             | Password of the private key used for TLS              | `swim87'bleat` |
| DOCKER_DTR                    | DTR fqdn:port Marathon will pull from                 | _empty_ = default dtr |
| SERVICE_VHOST                 | vhost fqdn on the marathon-lb vip                     | cassandra.dcosb.run.dcos.aws-usw02-dev.ice.predix.io |
| * USER_MGMT_PK_ALIAS          |                                                       | dcos-principal |
| * USER_MGMT_PK_PSW            |                                                       | 8BWTL0ie |
| SERVICE_ID                    |                                                       | cassandra |

Once you are satisfied with the values of these envvars, begin the build by issuing

```bash
sbt docker
```

This should create a docker image tagged `io.predix.dcosb/cassandra-dcos:1.0-SNAPSHOT` in your local daemon


## Usage

### Get private keys, certificates in place

All of the below take part in building the docker image therefore must be configured before `sbt docker` is run.

#### Broker principal private key

You need to make sure you have access to a DC/OS principal's private key, allowed to perform the following actions:

- install & uninstall packages, update services in cosmos
- read the mesos master/frameworks, master/state-summary, master/slaves API endpoints
- read the marathon/app API endpoints
- have access to the service API of started services in DC/OS

You then need to place this private key on the Akka KeyStore Manger store configured with
the store id `cassandra` ( see `dcosb.service-broker.aksm` in `application.conf`) and alias `dcos-principal`

This key is used to sign the JWT request to obtain a token for communication with the DC/OS admin router.

To generate such a key pair, run the following:

```bash
openssl genrsa -out cassandra-broker.pem 2048 && \
openssl rsa -in cassandra-broker.pem -pubout -out cassandra-broker.pub.pem && \
openssl pkcs12 -export -out cassandra.p12 -nocerts -in cassandra-broker.pem -name dcos-principal
```

You will then need to create your service account with the contents of `cassandra-broker.pub.pem`

####  Key pair for providing the OSB web service over TLS 

Get a hold of a private key and it's certificate for hosting the TLS wrapped web service port, and place them 
on the Akka KeyStore Manger store configured with the id `$BROKER_TLS_PK_CERT_STORE`
key alias `$BROKER_TLS_PK_CERT_ALIAS` and private key password `$BROKER_TLS_PK_PSW`

By default the above envvars point to a store provided by the `service-broker` artifact. (i.e. if you are ok with that self-signed
certificate, you have nothing to configure here)

#### Trust store

In order to securely establish connection with the DC/OS admin router, it's certificate must be imported in to
a key store that is then loaded in to the daemon as a "trust store"

There are 3 envvars ( `$BROKER_TRUST_STORE`, `$BROKER_TRUST_STORE_TYPE` and `$BROKER_TRUST_STORE_PSW` - `$BROKER_TRUST_STORE_CLASSPATH` should be set to
   `false` if bring your own file ) that configure this store. By default, a JKS store is provided by the `service-broker` artifact
that includes the mesosphere default certificate for CF3 and gedpredix AWS.

Additionally, the system trust store is also configured ( `{path: ${java.home}/lib/security/cacerts, password = "changeit"}` )


### Start the broker

From the root of the repo, with sbt

```bash
sbt -Dakka.loglevel=DEBUG run
```
    
After the docker image is built

```bash
docker run -p 8080:8080/tcp -ti -e "ADDITIONAL_OPTS=-Dakka.loglevel=DEBUG" --rm io.predix.dcosb/cassandra-dcos:1.0-SNAPSHOT
```
    
In Marathon

```bash
sbt parseMarathon # --> target/marathon.json
```

### Provision a 3 node cluster

```bash
http --verify=no -a apiuser:YYz3aN-kmw PUT https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1 parameters:='{"nodes":3,"cluster_name":"dcosb-cassandra-1", "cni": false}' organization_guid=SomeORG plan_id=basic service_id=SomeServiceGUID space_guid=SomeSpaceGUID
HTTP/1.1 202 Accepted
Content-Length: 22
Content-Type: application/json
Date: Tue, 21 Nov 2017 01:03:12 GMT
Server: akka-http/10.0.5

{
    "operation": "create"
}
```
    
### Monitor provisioning progress

```bash
http --verify=no -a apiuser:YYz3aN-kmw GET https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/last_operation/?operation=create
HTTP/1.1 200 OK
Content-Length: 66
Content-Type: application/json
Date: Tue, 21 Nov 2017 01:03:39 GMT
Server: akka-http/10.0.5

{
    "description": "node-0:[server] -> PENDING",
    "state": "in progress"
}
```
    
### Scale out cluster to 4 nodes
  
```bash
http --verify=no -a apiuser:YYz3aN-kmw PATCH https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1 service_id=SomeServiceGUID parameters:='{"nodes":4}'
HTTP/1.1 202 Accepted
Content-Length: 22
Content-Type: application/json
Date: Tue, 21 Nov 2017 01:05:18 GMT
Server: akka-http/10.0.5

{
    "operation": "update"
}
```

### Monitor scale-out progress

```bash
http --verify=no -a apiuser:YYz3aN-kmw GET https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/last_operation/?operation=update
HTTP/1.1 200 OK
Content-Length: 124
Content-Type: application/json
Date: Tue, 21 Nov 2017 01:06:03 GMT
Server: akka-http/10.0.5

{
    "description": "node-0:[server] -> COMPLETE, node-1:[server] -> STARTING, node-2:[server] -> PENDING",
    "state": "in progress"
}
```

### Create an application binding with the cluster

```bash
http --timeout=60 --verify=no -a apiuser:YYz3aN-kmw PUT https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/service_bindings/binding-1 service_id=SomeServiceGUID plan_id=basic bind_resource:='{"app_guid":"SomeAppGUID"}'
HTTP/1.1 200 OK
Content-Length: 469
Content-Type: application/json
Date: Tue, 09 Jan 2018 12:25:49 GMT
Server: akka-http/10.0.10

{
    "credentials": {
        "password": "aJQCgqfXfLyF7UyolhhfOJtZP6/aoXtt329xEa54oa1magopi+7wQj2fkNnUCwU/fmVap1QCO8tblW5Hr6kDZKvJ/Ub/Oca/bVCW7aaubhZmODtxGCGrycUJBFEDJO3obgZmofHgFiQ1cJfdzKmafnDOUnEWnrJ+p2Fsg453AG1CXD54OMOY+wnbIhLpJdgVE2+GthOuWXbetSsLToLtBczjhkWA7+4AoowZ/xwNyzlq1z7kKSaFxNxkgyA+2nHROd+eKSGyOCCNNB/NZ0kOQwyYB+Wo1N5o0re1KZwPc5iYbQa/0DsvzP1hlrmCkhBuEJvLcT214aw9OwS+nHUC1Q==",
        "username": "binding-1"
    },
    "nodes": [
        {
            "host": "node-0-server.dcosb-cassandra-1.mesos",
            "port": 9042
        }
    ]
}
```

### Un-bind the application from the cluster

```bash
http --timeout=60 --verify=no -a apiuser:YYz3aN-kmw DELETE https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/service_bindings/binding-1/?service_id=SomeServiceGUID\&plan_id=basic
HTTP/1.1 200 OK
Content-Length: 2
Content-Type: application/json
Date: Fri, 12 Jan 2018 16:01:14 GMT
Server: akka-http/10.0.10

{}
```

### De-provision the cluster

```bash
http --timeout=60 --verify=no -a apiuser:YYz3aN-kmw DELETE https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/?service_id=SomeServiceGUID\&plan_id=basic
HTTP/1.1 202 Accepted
Content-Length: 23
Content-Type: application/json
Date: Fri, 12 Jan 2018 16:09:47 GMT
Server: akka-http/10.0.10

{
    "operation": "destroy"
}
```

### Monitor the progress of the de-provisioning
```bash
http --verify=no -a apiuser:YYz3aN-kmw GET https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1/last_operation/?operation=destroy
HTTP/1.1 200 OK
Content-Length: 116
Content-Type: application/json
Date: Fri, 12 Jan 2018 16:10:23 GMT
Server: akka-http/10.0.10

{
    "description": "Resources pending clean up: 15 of 15, de-registration: deregister -> PENDING",
    "state": "in progress"
}

```

## Appendix

### Interacting with APIs behind the admin proxy, using a service account

for example, this is what the pipeline in `Jenkinsfile` does to send a marathon app
descriptor to the marathon API

```bash
#!/usr/bin/env bash

# Create a JWT 
JWT=$( \
HEADER=$(echo -n '{"alg":"RS256","typ":"JWT"}' | openssl enc -base64 -A | tr -d '=' | tr '/+' '_-') && \
PAYLOAD=$(echo -n '{"uid":"'$DEPLOYER_PRINCIPAL'"}' | openssl enc -base64 -A | tr -d '=' | tr '/+' '_-') && \
SIGNATURE=$(echo -n $HEADER'.'$PAYLOAD | openssl dgst -sha256 -binary -sign $DEPLOYER_PK | openssl enc -base64 -A | tr -d '=' | tr '/+' '_-') && \
echo $HEADER'.'$PAYLOAD'.'$SIGNATURE)

# Exchange JWT for DC/OS token
TOKEN=$(curl --insecure -d '{"uid":"'$DEPLOYER_PRINCIPAL'","token":"'$JWT'"}' -H 'Content-Type: application/json' -X POST $DCOS/acs/api/v1/auth/login | jq '.token')

# Create Marathon JSON
# sbt parseMarathon

# Send Marathon JSON to Marathon
curl -d "@target/marathon.json" -H "Authorization: token=$TOKEN" -H "Content-Type: application/json" -X POST $DCOS/service/marathon/v2/apps
```

### Managing keys, certificates

#### List contents of a PKCS#12 store

```bash
keytool -list -storetype PKCS12 -storepass 8BWTL0ie -keystore cassandra.p12 
```

#### Export the private key with alias `dcos-principal` to PEM format from a PKCS#12 store

```bash
openssl pkcs12 -in cassandra.p12 -nocerts -nodes -name dcos-principal -out dcos-principal.pem
```

#### Get admin password for a cluster

```bash
echo -n "admin,on:/dcosb/someorg/somespaceguid/cassandra/dcosb-cassandra-1" | openssl dgst -sha256 -binary -sign dcos-principal.pem | openssl enc -base64 -A
```

#### Get password for a bound account on a cluster

```bash
echo -n "binding:binding-1,on:/dcosb/someorg/somespaceguid/cassandra/dcosb-cassandra-1" | openssl dgst -sha256 -binary -sign dcos-principal.pem | openssl enc -base64 -A
```