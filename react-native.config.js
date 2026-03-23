module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: "import com.soundanalyzer.SoundRecorderPackage;",
        packageInstance: "new SoundRecorderPackage()",
      },
      ios: {},
    },
  },
};
