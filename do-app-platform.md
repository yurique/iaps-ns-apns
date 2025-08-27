### Deploying to Digital Ocean App Platform

* sign in into Digital Ocean
* create a Spaces bucket ($5/mo subscription fee)
    * go to `Spaces Object Storage` - https://cloud.digitalocean.com/spaces
    * click Create Bucket
    * select a region
    * choose a unique name, like `${YOUR_PREFIX}-iaps-ns-apns-server`
    * confirm
    * on the bucket overview page, switch to the `Settings` tab
    * take note of the endpoint at the top-right (`https://my-bucket-name.fra1.digitaloceanspaces.com`)
    * at the bottom - create a new Access Key
        * `Limited Access`
        * only check this newly created bucket in the list, select Read/Write/Delete in the permissions
        * create the key
        * record the access key ID and the secret key
* create an app
    * go to `App Platform` - https://cloud.digitalocean.com/apps
    * click Create App
    * choose deployment source - `Container Image`
    * select `Registry provider` - `Docker Hub` (or your own provider if you published the image elsewhere)
    * enter `Repository` - `yurique/iaps-ns-apns` (or your own image name)
    * enter `Image Tag or Digest` - enter latest version from this repository (or whatever version you published into your registry), for example `0.1.0`
    * enter credentials if needed (`yurique/iaps-ns-apns` is public, no credentials required)
    * click `Next`
    * select the instance size (the smallest one should be enough)
    * enter port `22626` for `Network` / `Public HTTP port`
    * add the following environment variables:
        * for each variable select `Run time` in the `Scope` dropdown, enable `Encrypt` for the `NSAPNS_S3_SECRET_KEY` variable
```dotenv
NSAPNS_S3_REGION=us-east-1
NSAPNS_S3_ENDPOINT=https://fra1.digitaloceanspaces.com # replace with the endpoint value for your bucket name, remove the bucket name at the beginning
NSAPNS_S3_BUCKET=my-bucket-name # replace with your bucket name
NSAPNS_S3_ROOT_PATH=storage # arbitrary value, "storage"
NSAPNS_S3_ACCESS_KEY=your access key ID here
NSAPNS_S3_SECRET_KEY=your secret access key here
JAVA_OPTS=-XX:+UseContainerSupport
```
* choose `Region`
* specify a unique name
* create the app
