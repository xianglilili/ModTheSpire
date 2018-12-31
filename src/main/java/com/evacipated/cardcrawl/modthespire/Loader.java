package com.evacipated.cardcrawl.modthespire;

import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;
import com.evacipated.cardcrawl.modthespire.steam.SteamSearch;
import com.evacipated.cardcrawl.modthespire.steam.SteamWorkshop;
import com.evacipated.cardcrawl.modthespire.ui.ModSelectWindow;
import com.vdurmont.semver4j.Semver;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.EmptyVisitor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.List;

public class Loader
{
    public static boolean DEBUG = false;
    public static boolean OUT_JAR = false;

    public static Semver MTS_VERSION;
    public static String MOD_DIR = "mods/";
    public static String STS_JAR = "desktop-1.0.jar";
    private static String MAC_STS_JAR = "SlayTheSpire.app/Contents/Resources/" + STS_JAR;
    private static String STS_JAR2 = "SlayTheSpire.jar";
    public static String COREPATCHES_JAR = "/corepatches.jar";
    public static String STS_PATCHED_JAR = "desktop-1.0-patched.jar";
    public static ModInfo[] MODINFOS;
    private static ClassPool POOL;

    public static SpireConfig MTS_CONFIG;
    public static String STS_VERSION = null;
    public static boolean STS_BETA = false;
    public static boolean allowBeta = false;

    static String[] ARGS;
    private static ModSelectWindow ex;

    public static boolean isModLoaded(String modID)
    {
        for (int i=0; i<MODINFOS.length; ++i) {
            if (modID.equals(MODINFOS[i].ID)) {
                return true;
            }
        }
        return false;
    }

    public static ClassPool getClassPool()
    {
        return POOL;
    }

    public static void main(String[] args)
    {
        ARGS = args;
        try {
            Properties defaults = new Properties();
            defaults.setProperty("debug", Boolean.toString(false));
            defaults.setProperty("out-jar", Boolean.toString(false));
            defaults.putAll(ModSelectWindow.getDefaults());
            MTS_CONFIG = new SpireConfig(null, "ModTheSpire", defaults);
        } catch (IOException e) {
            e.printStackTrace();
        }
        DEBUG = MTS_CONFIG.getBool("debug");
        OUT_JAR = MTS_CONFIG.getBool("out-jar");

        if (Arrays.asList(args).contains("--debug")) {
            DEBUG = true;
        }
        
        if (Arrays.asList(args).contains("--out-jar")) {
            OUT_JAR = true;
        }

        allowBeta = true;
        if (Arrays.asList(args).contains("--allow-beta")) {
            allowBeta = true;
        }

        try {
            Properties properties = new Properties();
            properties.load(Loader.class.getResourceAsStream("/META-INF/version.prop"));
            MTS_VERSION = ModInfo.safeVersion(properties.getProperty("version"));
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        // Check if we are desktop-1.0.jar
        try {
            String thisJarName = new File(Loader.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getName();
            if (thisJarName.equals(STS_JAR)) {
                STS_JAR = STS_JAR2;
            }
        } catch (URISyntaxException e) {
            // NOP
        }
        // Check that desktop-1.0.jar exists
        {
            File tmp = new File(STS_JAR);
            if (!tmp.exists()) {
                // Search for Steam install
                String steamJar = SteamSearch.findDesktopJar();
                if (steamJar != null && new File(steamJar).exists()) {
                    STS_JAR = steamJar;
                } else {
                    // Check if for the Mac version
                    tmp = new File(MAC_STS_JAR);
                    checkFileInfo(tmp);
                    if (!tmp.exists()) {
                        checkFileInfo(new File("SlayTheSpire.app"));
                        checkFileInfo(new File("SlayTheSpire.app/Contents"));
                        checkFileInfo(new File("SlayTheSpire.app/Contents/Resources"));

                        JOptionPane.showMessageDialog(null, "Unable to find '" + STS_JAR + "'");
                        return;
                    } else {
                        System.out.println("Using Mac version at: " + MAC_STS_JAR);
                        STS_JAR = MAC_STS_JAR;
                    }
                }
            }
        }

        List<SteamSearch.WorkshopInfo> workshopInfos = new ArrayList<>();
        try {
            System.out.println("Searching for Workshop items...");
            String path = SteamWorkshop.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            path = URLDecoder.decode(path, "utf-8");
            path = new File(path).getPath();
            ProcessBuilder pb = new ProcessBuilder(
                SteamSearch.findJRE(),
                "-cp", path,
                "com.evacipated.cardcrawl.modthespire.steam.SteamWorkshop"
            );
            Process p = pb.start();

            BufferedReader ereader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String eline = null;
            while ((eline = ereader.readLine()) != null) {
                System.err.println("ERROR: " + eline);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String first = null;
            String installPath = null;
            String line = null;
            while ((line = reader.readLine()) != null) {
                if (first == null) {
                    first = line;
                    System.out.println(first);
                } else if (installPath == null) {
                    installPath = line;
                } else {
                    SteamSearch.WorkshopInfo info = new SteamSearch.WorkshopInfo(installPath, line);
                    if (!info.hasTag("tool") && !info.hasTag("tools")) {
                        workshopInfos.add(info);
                    }
                    installPath = null;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        for (SteamSearch.WorkshopInfo info : workshopInfos) {
            System.out.println(info.getInstallPath());
            System.out.println(Arrays.toString(info.getTags().toArray()));
        }

        findGameVersion();

        EventQueue.invokeLater(() -> {
            ModInfo[] modInfos = getAllMods(workshopInfos);
            ex = new ModSelectWindow(modInfos);
            ex.setVisible(true);

            ex.warnAboutMissingVersions();

            String java_version = System.getProperty("java.version");
            if (!java_version.startsWith("1.8")) {
                String msg = "ModTheSpire requires Java version 8 to run properly.\nYou are currently using Java " + java_version;
                JOptionPane.showMessageDialog(null, msg, "Warning", JOptionPane.WARNING_MESSAGE);
            }

            ex.startCheckingForMTSUpdate();
        });
    }

    public static void closeWindow()
    {
        ex.dispatchEvent(new WindowEvent(ex, WindowEvent.WINDOW_CLOSING));
    }

    // runMods - sets up the ClassLoader, sets the isModded flag and launches the game
    public static void runMods(File[] modJars)
    {
        if (Loader.DEBUG) {
            System.out.println("Running with debug mode turned ON...");
            System.out.println();
        }
        try {
            ModInfo[] modInfos = buildInfoArray(modJars);
            checkDependencies(modInfos);
            modInfos = orderDependencies(modInfos);
            MODINFOS = modInfos;

            printMTSInfo();

            MTSClassLoader loader = new MTSClassLoader(Loader.class.getResourceAsStream(COREPATCHES_JAR), buildUrlArray(modInfos), Loader.class.getClassLoader());

            if (modJars.length > 0) {
                MTSClassLoader tmpPatchingLoader = new MTSClassLoader(Loader.class.getResourceAsStream(COREPATCHES_JAR), buildUrlArray(modInfos), Loader.class.getClassLoader());
                
                System.out.println("Begin patching...");
                ClassPool pool = new MTSClassPool(tmpPatchingLoader);
                pool.insertClassPath(new LoaderClassPath(tmpPatchingLoader));
                tmpPatchingLoader.addStreamToClassPool(pool); // Inserts infront of above path
                SortedMap<String, CtClass> ctClasses = new TreeMap<>();
                // Find and inject core patches
                System.out.println("Finding core patches...");
                for (CtClass cls : Patcher.injectPatches(tmpPatchingLoader, pool, Patcher.findPatches(new URL[]{Loader.class.getResource(Loader.COREPATCHES_JAR)}))) {
                    ctClasses.put(countSuperClasses(cls) + cls.getName(), cls);
                }
                // Find and inject mod patches
                System.out.println("Finding patches...");
                for (CtClass cls : Patcher.injectPatches(tmpPatchingLoader, pool, Patcher.findPatches(MODINFOS))) {
                    ctClasses.put(countSuperClasses(cls) + cls.getName(), cls);
                }

                for (CtClass cls : Patcher.patchOverrides(tmpPatchingLoader, pool, MODINFOS)) {
                    ctClasses.put(countSuperClasses(cls) + cls.getName(), cls);
                }

                Patcher.finalizePatches(tmpPatchingLoader);
                Patcher.compilePatches(loader, ctClasses);

                ctClasses.clear();
                tmpPatchingLoader.close();

                POOL = new MTSClassPool(loader);
                POOL.insertClassPath(new LoaderClassPath(loader));
                loader.addStreamToClassPool(POOL);

                System.out.printf("Patching enums...");
                Patcher.patchEnums(loader, Loader.class.getResource(Loader.COREPATCHES_JAR));
                // Patch SpireEnums from mods
                Patcher.patchEnums(loader, modInfos);
                System.out.println("Done.");
                System.out.println();

                // Set Settings.isModded = true
                System.out.printf("Setting isModded = true...");
                System.out.flush();
                Class<?> Settings = loader.loadClass("com.megacrit.cardcrawl.core.Settings");
                Field isModded = Settings.getDeclaredField("isModded");
                isModded.set(null, true);
                System.out.println("Done.");
                System.out.println();

                // Add ModTheSpire section to CardCrawlGame.VERSION_NUM
                System.out.printf("Adding ModTheSpire to version...");
                System.out.flush();
                Class<?> CardCrawlGame = loader.loadClass("com.megacrit.cardcrawl.core.CardCrawlGame");
                Field VERSION_NUM = CardCrawlGame.getDeclaredField("VERSION_NUM");
                String oldVersion = (String) VERSION_NUM.get(null);
                VERSION_NUM.set(null, oldVersion + " [ModTheSpire " + MTS_VERSION + "]");
                System.out.println("Done.");
                System.out.println();
                
                // Output JAR if requested
                if (Loader.OUT_JAR) {
                    System.out.printf("Dumping JAR...");
                    OutJar.dumpJar(loader, pool, STS_PATCHED_JAR);
                    System.out.println("Done.");
                    return;
                }

                // Initialize any mods that implement SpireInitializer.initialize()
                System.out.println("Initializing mods...");
                Patcher.initializeMods(loader, modInfos);
                System.out.println("Done.");
                System.out.println();
            }

            System.out.println("Starting game...");
            Class<?> cls = loader.loadClass("com.megacrit.cardcrawl.desktop.DesktopLauncher");
            Method method = cls.getDeclaredMethod("main", String[].class);
            method.invoke(null, (Object) ARGS);
        } catch (MissingDependencyException e) {
            System.err.println("ERROR: " + e.getMessage());
            JOptionPane.showMessageDialog(null, e.getMessage(), "Missing Dependency", JOptionPane.ERROR_MESSAGE);
        } catch (DuplicateModIDException e) {
            System.err.println("ERROR: " + e.getMessage());
            JOptionPane.showMessageDialog(null, e.getMessage(), "Duplicate Mod ID", JOptionPane.ERROR_MESSAGE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setGameVersion(String versionString)
    {
        if (versionString.startsWith("(") && versionString.endsWith(")")) {
            versionString = versionString.substring(1, versionString.length()-1);
        }
        STS_VERSION = versionString;
    }

    private static void findGameVersion()
    {
        try {
            URLClassLoader tmpLoader = new URLClassLoader(new URL[]{new File(STS_JAR).toURI().toURL()});
            // Read CardCrawlGame.VERSION_NUM
            InputStream in = tmpLoader.getResourceAsStream("com/megacrit/cardcrawl/core/CardCrawlGame.class");
            ClassReader classReader = new ClassReader(in);

            classReader.accept(new GameVersionFinder(), 0);

            // Read Settings.isBeta
            InputStream in2 = tmpLoader.getResourceAsStream("com/megacrit/cardcrawl/core/Settings.class");
            ClassReader classReader2 = new ClassReader(in2);

            classReader2.accept(new GameBetaFinder(new EmptyVisitor()), 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // buildUrlArray - builds the URL array to pass to the ClassLoader
    private static URL[] buildUrlArray(ModInfo[] modInfos) throws MalformedURLException
    {
        URL[] urls = new URL[modInfos.length + 1];
        for (int i = 0; i < modInfos.length; i++) {
            urls[i] = modInfos[i].jarURL;
        }

        urls[modInfos.length] = new File(STS_JAR).toURI().toURL();
        return urls;
    }

    private static ModInfo[] buildInfoArray(File[] modJars)
    {
        ModInfo[] infos = new ModInfo[modJars.length];
        for (int i = 0; i < modJars.length; ++i) {
            infos[i] = ModInfo.ReadModInfo(modJars[i]);
        }
        return infos;
    }

    // getAllModFiles - returns a File array containing all of the JAR files in the mods directory
    private static File[] getAllModFiles(String directory)
    {
        File file = new File(directory);
        if (!file.exists() || !file.isDirectory()) {
            return new File[0];
        }

        File[] files = file.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".jar");
            }
        });

        if (files == null || files.length == 0) {
            return new File[0];
        }
        return files;
    }

    private static ModInfo[] getAllMods(List<SteamSearch.WorkshopInfo> workshopInfos)
    {
        List<ModInfo> modInfos = new ArrayList<>();

        // "mods/" directory
        for (File f : getAllModFiles(MOD_DIR)) {
            ModInfo info = ModInfo.ReadModInfo(f);
            if (info != null) {
                if (modInfos.stream().noneMatch(i -> i.ID == null || i.ID.equals(info.ID))) {
                    modInfos.add(info);
                }
            }
        }

        // Workshop content
        for (SteamSearch.WorkshopInfo workshopInfo : workshopInfos) {
            for (File f : getAllModFiles(workshopInfo.getInstallPath().toString())) {
                ModInfo info = ModInfo.ReadModInfo(f);
                if (info != null) {
                    // Disable the update json url for workshop content
                    info.UpdateJSON = null;
                    info.isWorkshop = true;
                    if (modInfos.stream().noneMatch(i -> i.ID == null || i.ID.equals(info.ID))) {
                        modInfos.add(info);
                    }
                }
            }
        }

        // Convert to ModInfo, don't include duplicate mod IDs


        return modInfos.toArray(new ModInfo[0]);
    }

    private static void printMTSInfo()
    {
        System.out.println("ModVersion Info:");
        System.out.printf(" - Java version (%s)\n", System.getProperty("java.version"));
        System.out.printf(" - Slay the Spire (%s)", STS_VERSION);
        if (STS_BETA) {
            System.out.printf(" BETA");
        }
        System.out.printf("\n");
        System.out.printf(" - ModTheSpire (%s)\n", MTS_VERSION);
        System.out.printf("Mod list:\n");
        for (ModInfo info : MODINFOS) {
            System.out.printf(" - %s", info.getIDName());
            if (info.ModVersion != null) {
                System.out.printf(" (%s)", info.ModVersion);
            }
            System.out.println();
        }
        System.out.println();
    }

    private static void checkDependencies(ModInfo[] modinfos) throws MissingDependencyException, DuplicateModIDException
    {
        Map<String, ModInfo> dependencyMap = new HashMap<>();
        for (final ModInfo info : modinfos) {
            if (info.ID != null) {
                if (!dependencyMap.containsKey(info.ID)) {
                    dependencyMap.put(info.ID, info);
                } else {
                    throw new DuplicateModIDException(dependencyMap.get(info.ID), info);
                }
            }
        }

        for (final ModInfo info : modinfos) {
            for (String dependency : info.Dependencies) {
                boolean has = false;
                for (final ModInfo dependinfo : modinfos) {
                    if (dependinfo.ID != null && dependinfo.ID.equals(dependency)) {
                        has = true;
                        break;
                    }
                }
                if (!has) {
                    throw new MissingDependencyException(info, dependency);
                }
            }
        }
    }

    private static int findDependencyIndex(ModInfo[] modInfos, String dependencyID)
    {
        for (int i=0; i<modInfos.length; ++i) {
            if (modInfos[i] != null && modInfos[i].ID != null) {
                if (modInfos[i].ID.equals(dependencyID)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static ModInfo[] orderDependencies(ModInfo[] modInfos) throws CyclicDependencyException
    {
        GraphTS<ModInfo> g = new GraphTS<>();

        for (final ModInfo info : modInfos) {
            g.addVertex(info);
        }

        for (int i=0; i<modInfos.length; ++i) {
            for (String dependency : modInfos[i].Dependencies) {
                g.addEdge(findDependencyIndex(modInfos, dependency), i);
            }
            for (String optionalDependency : modInfos[i].OptionalDependencies) {
                int idx = findDependencyIndex(modInfos, optionalDependency);
                if (idx != -1) {
                    g.addEdge(idx, i);
                }
            }
        }

        g.tsortStable();

        return g.sortedArray.toArray(new ModInfo[g.sortedArray.size()]);
    }

    private static void checkFileInfo(File file)
    {
        System.out.printf(file.getName() + ": ");
        System.out.println(file.exists() ? "Exists" : "Does not exist");

        if (file.exists()) {
            System.out.printf("Type: ");
            if (file.isFile()) {
                System.out.println("File");
            } else if (file.isDirectory()) {
                System.out.println("Directory");
                System.out.println("Contents:");
                for (File subfile : Objects.requireNonNull(file.listFiles())) {
                    System.out.println("  " + subfile.getName());
                }
            } else {
                System.out.println("Unknown");
            }
        }
    }

    private static int countSuperClasses(CtClass cls)
    {
        String name = cls.getName();
        int count = 0;

        while (cls != null) {
            try {
                cls = cls.getSuperclass();
            } catch (NotFoundException e) {
                break;
            }
            ++count;
        }

        return count;
    }
}
