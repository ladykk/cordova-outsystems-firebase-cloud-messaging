"use strict"

var path = require("path");
var fs = require("fs");

var constants = {
  platforms: "platforms",
  android: {
    platform: "android",
    wwwFolder: "www",
    soundFileExtension: ".wav",
    getSoundDestinationFolder: function() {
      return "platforms/android/app/src/main/res/raw";
    },
    getWWWFolder: function() {
      return "www";
    }
  },
  ios: {
    platform: "ios",
    wwwFolder: "www",
    soundFileExtension: ".wav",
    getSoundDestinationFolder: function() {
      return "platforms/ios/www";
    },
    getWWWFolder: function() {
      return "platforms/ios/www";
    }
  }
};

function checkIfFileOrFolderExists(path) {
  return fs.existsSync(path);
}

function removeFile(path){
  fs.unlinkSync(path)
}

function removeFolder(path){
  fs.rmSync(path, { recursive: true })
}

function getFilesFromPath(path) {
  return fs.readdirSync(path);
}

function createOrCheckIfFolderExists(path) {
  if (!fs.existsSync(path)) {
    fs.mkdirSync(path);
  }
}

function getPlatformConfigs(platform) {
  if (platform === constants.android.platform) {
    return constants.android;
  } else if (platform === constants.ios.platform) {
    return constants.ios;
  }
}

function getPlatformSoundPath(context, platformConfig){
  let projectRoot = context.opts.projectRoot;
  let platformPath;

  if(platformConfig === constants.android){
    platformPath = path.join(projectRoot, `platforms/android/www`);
  } else {
    let appName = getAppName(context)
    platformPath = path.join(projectRoot, `platforms/ios/${appName}/Resources/www`);   
  }
      
  if(!fs.existsSync(platformPath)){
    platformPath = path.join(projectRoot, platformConfig.getWWWFolder());
  }
  
  return platformPath
}

function isCordovaAbove(context, version) {
  let cordovaVersion = context.opts.cordova.version;
  let sp = cordovaVersion.split('.');
  return parseInt(sp[0]) >= version;
}

function copyFromSourceToDestPath(defer, sourcePath, destPath) {
  fs.createReadStream(sourcePath).pipe(fs.createWriteStream(destPath))
  .on("close", function () {
    console.log(`Finished copying ${sourcePath}.`);
    defer.resolve();
  })
  .on("error", function (err) {
    console.log(err);
    throw new Error (`OUTSYSTEMS_PLUGIN_ERROR: Something went wrong when trying to copy sounds files. Please check the logs for more information.`);
  });
}

function isAndroid(platform){
  return platform === constants.android.platform
}

function getAppName(context) {
  let ConfigParser = context.requireCordovaModule("cordova-lib").configparser;
  let config = new ConfigParser("config.xml");
  return config.name();
}

module.exports = {
  isCordovaAbove,
  getPlatformConfigs,
  copyFromSourceToDestPath,
  getFilesFromPath,
  createOrCheckIfFolderExists,
  checkIfFileOrFolderExists,
  removeFile,
  removeFolder,
  isAndroid,
  getAppName,
  getPlatformSoundPath
};