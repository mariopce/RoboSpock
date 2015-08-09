package pl.polidea.robospock.internal;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.*;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.robolectric.annotation.Config;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.InstrumentingClassLoader;
import org.robolectric.internal.dependency.CachedDependencyResolver;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.dependency.LocalDependencyResolver;
import org.robolectric.internal.dependency.MavenDependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.res.ResourceLoader;
import org.spockframework.runtime.Sputnik;
import org.spockframework.runtime.model.SpecInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URL;
import java.security.SecureRandom;
import java.util.*;

public class RoboSputnik extends Runner implements Filterable, Sortable {



    private static final Map<AndroidManifest, ResourceLoader> resourceLoadersByAppManifest = new HashMap<AndroidManifest, ResourceLoader>();

    private static Class<? extends RoboSputnik> lastTestRunnerClass;
    private static SdkConfig lastSdkConfig;
    private static final Map<ManifestIdentifier, AndroidManifest> appManifestsByFile = new HashMap<ManifestIdentifier, AndroidManifest>();
    private static SdkEnvironment lastSdkEnvironment;


    private Object sputnik;

    static {
        new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
    }

    private DependencyResolver dependencyResolver;

    public RoboSputnik(Class<?> clazz) throws InitializationError {

        // Ripped from RobolectricTestRunner

        final Config config = getConfig(clazz);
        AndroidManifest appManifest = getAppManifest(config);
        SdkEnvironment sdkEnvironment = getEnvironment(appManifest, config);
        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        Class bootstrappedTestClass = sdkEnvironment.bootstrappedClass(clazz);
        // Since we have bootstrappedClass we may properly initialize

        try {

            this.sputnik = sdkEnvironment
                    .bootstrappedClass(Sputnik.class)
                    .getConstructor(Class.class)
                    .newInstance(bootstrappedTestClass);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // let's manually add our initializers

        for (Method method : sputnik.getClass().getDeclaredMethods()) {
            if ("getSpec".equals(method.getName())) {
                method.setAccessible(true);
                try {
                    Object spec = method.invoke(sputnik);

                    // Interceptor registers on construction
                    sdkEnvironment
                            .bootstrappedClass(RoboSpockInterceptor.class)
                            .getConstructor(
                                    sdkEnvironment.bootstrappedClass(SpecInfo.class),
                                    SdkEnvironment.class,
                                    Config.class,
                                    AndroidManifest.class
                            ).newInstance(spec, sdkEnvironment, config, appManifest);

                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                } catch (InstantiationException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public Config getConfig(Class<?> clazz) {
        Config config = defaultsFor(Config.class);

        Config globalConfig = Config.Implementation.fromProperties(getConfigProperties());
        if (globalConfig != null) {
            config = new Config.Implementation(config, globalConfig);
        }

        Config classConfig = clazz.getAnnotation(Config.class);
        if (classConfig != null) {
            config = new Config.Implementation(config, classConfig);
        }

        return config;
    }

    private static <A extends Annotation> A defaultsFor(Class<A> annotation) {
        //noinspection unchecked
        return (A) Proxy.newProxyInstance(annotation.getClassLoader(),
                new Class[]{annotation}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                        return method.getDefaultValue();
                    }
                });
    }

    protected Properties getConfigProperties() {
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream resourceAsStream = classLoader.getResourceAsStream("org.robolectric.Config.properties");
        if (resourceAsStream == null) return null;
        Properties properties = new Properties();
        try {
            properties.load(resourceAsStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return properties;
    }

    protected AndroidManifest getAppManifest(Config config) {
        if (config.manifest().equals(Config.NONE)) {
            return null;
        }

        String manifestProperty = System.getProperty("android.manifest");
        String resourcesProperty = System.getProperty("android.resources");
        String assetsProperty = System.getProperty("android.assets");
        String packageName = System.getProperty("android.package");

        FsFile baseDir;
        FsFile manifestFile;
        FsFile resDir;
        FsFile assetDir;

        boolean defaultManifest = config.manifest().equals(Config.DEFAULT);
        if (defaultManifest && manifestProperty != null) {
            manifestFile = Fs.fileFromPath(manifestProperty);
            baseDir = manifestFile.getParent();
        } else {
            manifestFile = getBaseDir().join(defaultManifest ? AndroidManifest.DEFAULT_MANIFEST_NAME : config.manifest());
            baseDir = manifestFile.getParent();
        }

        boolean defaultRes = Config.DEFAULT_RES_FOLDER.equals(config.resourceDir());
        if (defaultRes && resourcesProperty != null) {
            resDir = Fs.fileFromPath(resourcesProperty);
        } else {
            resDir = baseDir.join(config.resourceDir());
        }

        boolean defaultAssets = Config.DEFAULT_ASSET_FOLDER.equals(config.assetDir());
        if (defaultAssets && assetsProperty != null) {
            assetDir = Fs.fileFromPath(assetsProperty);
        } else {
            assetDir = baseDir.join(config.assetDir());
        }

        String configPackageName = config.packageName();
        if (configPackageName != null && !configPackageName.isEmpty()) {
            packageName = configPackageName;
        }

        List<FsFile> libraryDirs = null;
        if (config.libraries().length > 0) {
            libraryDirs = new ArrayList<FsFile>();
            for (String libraryDirName : config.libraries()) {
                libraryDirs.add(baseDir.join(libraryDirName));
            }
        }


        ManifestIdentifier identifier = new ManifestIdentifier(manifestFile, resDir, assetDir, packageName, libraryDirs);
        synchronized (appManifestsByFile) {
            AndroidManifest appManifest;
            appManifest = appManifestsByFile.get(identifier);
            if (appManifest == null) {
                appManifest = createAppManifest(manifestFile, resDir, assetDir, packageName);
                if (libraryDirs != null) {
                    appManifest.setLibraryDirectories(libraryDirs);
                }
                appManifestsByFile.put(identifier, appManifest);
            }
            return appManifest;
        }
    }

    protected AndroidManifest createAppManifest(FsFile manifestFile, FsFile resDir, FsFile assetDir, String packageName) {
        if (!manifestFile.exists()) {
            System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
            System.out.println("Falling back to the Android OS resources only.");
            System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
            return null;
        }

        System.out.println("Robolectric assets directory: " + assetDir.getPath());
        System.out.println("   Robolectric res directory: " + resDir.getPath());
        System.out.println("   Robolectric manifest path: " + manifestFile.getPath());
        System.out.println("    Robolectric package name: " + packageName);
        return new AndroidManifest(manifestFile, resDir, assetDir, packageName);
    }

    public InstrumentationConfiguration createClassLoaderConfig() {
        return InstrumentationConfiguration.newBuilder().build();
    }

    private static class ManifestIdentifier {
        private final FsFile manifestFile;
        private final FsFile resDir;
        private final FsFile assetDir;
        private final String packageName;
        private final List<FsFile> libraryDirs;

        public ManifestIdentifier(FsFile manifestFile, FsFile resDir, FsFile assetDir, String packageName,
                                  List<FsFile> libraryDirs) {
            this.manifestFile = manifestFile;
            this.resDir = resDir;
            this.assetDir = assetDir;
            this.packageName = packageName;
            this.libraryDirs = libraryDirs != null ? libraryDirs : Collections.<FsFile>emptyList();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ManifestIdentifier that = (ManifestIdentifier) o;

            return assetDir.equals(that.assetDir)
                    && libraryDirs.equals(that.libraryDirs)
                    && manifestFile.equals(that.manifestFile)
                    && resDir.equals(that.resDir)
                    && ((packageName == null && that.packageName == null) || (packageName != null && packageName.equals(that.packageName)));
        }

        @Override
        public int hashCode() {
            int result = manifestFile.hashCode();
            result = 31 * result + resDir.hashCode();
            result = 31 * result + assetDir.hashCode();
            result = 31 * result + (packageName == null ? 0 : packageName.hashCode());
            result = 31 * result + libraryDirs.hashCode();
            return result;
        }
    }

    protected FsFile getBaseDir() {
        return Fs.currentDirectory();
    }

    protected AndroidManifest createAppManifest(FsFile manifestFile, FsFile resDir, FsFile assetDir) {
        if (!manifestFile.exists()) {
            System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
            System.out.println("Falling back to the Android OS resources only.");
            System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
            return null;
        }
        AndroidManifest manifest = new AndroidManifest(manifestFile, resDir, assetDir);
        manifest.setPackageName(System.getProperty("android.package"));
        return manifest;
    }

    protected AndroidManifest createAppManifestFromProperty(FsFile manifestFile) {
        String resProperty = System.getProperty("android.resources");
        String assetsProperty = System.getProperty("android.assets");
        AndroidManifest manifest = new AndroidManifest(manifestFile, Fs.fileFromPath(resProperty), Fs.fileFromPath(assetsProperty));
        String packageProperty = System.getProperty("android.package");

        if (packageProperty != null) {
            try {
                setPackageName(manifest, packageProperty);
            } catch (IllegalArgumentException e) {
                System.out.println("WARNING: Faild to set package name for " + manifestFile.getPath() + ".");
            }
        }
        return manifest;
    }


    private void setPackageName(AndroidManifest manifest, String packageName) {
        Class<AndroidManifest> type = AndroidManifest.class;
        try {
            Method setPackageNameMethod = type.getMethod("setPackageName", String.class);
            setPackageNameMethod.setAccessible(true);
            setPackageNameMethod.invoke(manifest, packageName);
            return;
        } catch (NoSuchMethodException e) {
            try {

                //Force execute parseAndroidManifest.
                manifest.getPackageName();

                Field packageNameField = type.getDeclaredField("packageName");
                packageNameField.setAccessible(true);
                packageNameField.set(manifest, packageName);
                return;
            } catch (Exception fieldError) {
                throw new IllegalArgumentException(fieldError);
            }
        } catch (Exception methodError) {
            throw new IllegalArgumentException(methodError);
        }
    }

    private SdkEnvironment getEnvironment(final AndroidManifest appManifest, final Config config) {
        final SdkConfig sdkConfig = pickSdkVersion(appManifest, config);

        // keep the most recently-used SdkEnvironment strongly reachable to prevent thrashing in low-memory situations.
        if (getClass().equals(lastTestRunnerClass) && sdkConfig.equals(sdkConfig)) {
            return lastSdkEnvironment;
        }

        lastTestRunnerClass = null;
        lastSdkConfig = null;

        lastSdkEnvironment = createSdkEnvironment(sdkConfig);
        lastTestRunnerClass = getClass();
        lastSdkConfig = sdkConfig;
        return lastSdkEnvironment;
    }

    public SdkEnvironment createSdkEnvironment(SdkConfig sdkConfig) {
        System.out.println("createSdkEnvironment for " +sdkConfig);
        InstrumentationConfiguration config = createClassLoaderConfig();
        ClassLoader robolectricClassLoader = createRobolectricClassLoader(config, sdkConfig);
        return new SdkEnvironment(sdkConfig, robolectricClassLoader);
    }

    protected ClassLoader createRobolectricClassLoader(InstrumentationConfiguration config, SdkConfig sdkConfig) {
        URL[] urls = getJarResolver().getLocalArtifactUrls(sdkConfig.getSdkClasspathDependencies());
        return new InstrumentingClassLoader(config, urls);
    }

    protected DependencyResolver getJarResolver() {
        if (dependencyResolver == null) {
            if (Boolean.getBoolean("robolectric.offline")) {
                String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
                dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
            } else {
                File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");
                cacheDir.mkdir();

                if (cacheDir.exists()) {
                    dependencyResolver = new CachedDependencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
                } else {
                    dependencyResolver = new MavenDependencyResolver();
                }
            }
        }

        return dependencyResolver;
    }


    private SdkConfig pickSdkVersion(AndroidManifest appManifest, Config config) {
        if (config != null && config.sdk().length > 1) {
            throw new IllegalArgumentException("RobolectricTestRunner does not support multiple values for @Config.sdk");
        } else if (config != null && config.sdk().length == 1) {
            return new SdkConfig(config.sdk()[0]);
        } else if (appManifest != null) {
            return new SdkConfig(appManifest.getTargetSdkVersion());
        } else {
            return new SdkConfig(SdkConfig.FALLBACK_SDK_VERSION);
        }
    }

    public Description getDescription() {
        return ((Runner) sputnik).getDescription();
    }

    public void run(RunNotifier notifier) {
        ((Runner) sputnik).run(notifier);
    }

    public void filter(Filter filter) throws NoTestsRemainException {
        ((Filterable) sputnik).filter(filter);
    }

    public void sort(Sorter sorter) {
        ((Sortable) sputnik).sort(sorter);
    }


}
