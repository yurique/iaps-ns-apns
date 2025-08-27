### frontend build

```shell
npm install tailwindcss @tailwindcss/cli
# to update css
./prepare-frontend.sh
```

### backend 

(sbt build tool is needed: https://www.scala-sbt.org/download/)

* running locally:

create/edit the `.env` file in the repository root, put the environment variables as needed (described in the `environment variables` section below):
```dotenv
NSAPNS_STORAGE_PATH=/Users/yurique/git/GitHub/artificial-pancreas/ns-apns/.storage
# OR (example for DigitalOcean spaces)
NSAPNS_S3_REGION=us-east-1
NSAPNS_S3_ENDPOINT=https://fra1.digitaloceanS3.com
NSAPNS_S3_BUCKET=my-bucket
NSAPNS_S3_ROOT_PATH=storage
NSAPNS_S3_ACCESS_KEY=my-access-key-id
NSAPNS_S3_SECRET_KEY=my-secret-access-key
```

```sh
$> sbt
sbt:iaps-ns-apns> re-start
```

* building a local docker image:
```sh
$> sbt
sbt:iaps-ns-apns> Docker/publishLocal
```

* building and pushing to docker registry:
```sh
export NSAPNS_BUILD_DOCKER_PACKAGE_NAME=my-docker-hub-account/iaps-ns-apns
$> sbt
sbt:iaps-ns-apns> Docker/publish
```

### deploying to cloud

Publish the server image to your docker registry using the command described above.
Or use the `yurique/iaps-ns-apns:LATEST_VERSION` image published to Docker Hub. Find the latest version in the releases in this repository (for example, `yurique/iaps-ns-apns:0.1.0`).

Using the hosting platform of your choice, start a Docker container using the image and tag. The only configuration needed is the environment variables. The HTTP port is `22626`.

(See also [do-app-platform.md](do-app-platform.md))

### environment variables

* `NSAPNS_REGISTRY_TOKEN` - if set, private key registration/unregistration will require this token to be entered

For simple file-system storage:
* `NSAPNS_STORAGE_PATH` - path to a directory to be used for persistence 

For S3-like object storage:
* `NSAPNS_S3_REGION` - s3 region
* `NSAPNS_S3_ENDPOINT` - s3 endpoint (optional)
* `NSAPNS_S3_BUCKET` - bucket name
* `NSAPNS_S3_ROOT_PATH` - path within the bucket 
* `NSAPNS_S3_ACCESS_KEY` - s3 access key id
* `NSAPNS_S3_SECRET_KEY` - secret access key

### Developer private key 

Once the server is running, go to https://your-server-address/key-registration (or http://localhost:22626 when running locally) to upload your Apple Developer private key for your Bundle ID.


