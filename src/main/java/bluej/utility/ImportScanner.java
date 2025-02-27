/*
 This file is part of the BlueJ program. 
 Copyright (C) 2014,2015,2016,2017  Michael Kolling and John Rosenberg

 This program is free software; you can redistribute it and/or 
 modify it under the terms of the GNU General Public License 
 as published by the Free Software Foundation; either version 2 
 of the License, or (at your option) any later version. 

 This program is distributed in the hope that it will be useful, 
 but WITHOUT ANY WARRANTY; without even the implied warranty of 
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 GNU General Public License for more details. 

 You should have received a copy of the GNU General Public License 
 along with this program; if not, write to the Free Software 
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA. 

 This file is subject to the Classpath exception as provided in the  
 LICENSE.txt file that accompanied this code.
 */
package bluej.utility;

import bluej.Boot;
import bluej.Config;
import bluej.parser.ImportedTypeCompletion;
import bluej.pkgmgr.JavadocResolver;
import bluej.pkgmgr.Project;
import bluej.stride.generic.AssistContentThreadSafe;
import com.google.common.collect.Sets;
import javafx.application.Platform;
import nu.xom.*;
import org.reflections.Reflections;
import org.reflections.ReflectionsException;
import org.reflections.Store;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import threadchecker.OnThread;
import threadchecker.Tag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.reflections.ReflectionUtils.forName;

/**
 * A class which manages scanning the classpath for available imports.
 */
public class ImportScanner {
    // A lock item :
    private final Object monitor = new Object();
    // Root package with "" as ident.
    private CompletableFuture<RootPackageInfo> root;
    // The Reflections library we use to actually do the scanning:
    private Reflections reflections;
    // The Project which we are scanning for:
    private Project project;

    public ImportScanner(Project project) {
        this.project = project;
    }

    /**
     * For each package that we scan, we hold one PackgeInfo with details on the items
     * in that package, and links to any subpackages.  Thus the root of this tree
     * is a single PackageInfo representing the unnamed package (held in this.root).
     */
    private class PackageInfo {
        // Value can be null if details not loaded yet
        public final HashMap<String, AssistContentThreadSafe> types = new HashMap<>();
        public final HashMap<String, PackageInfo> subPackages = new HashMap<>();

        // Records a class with the given name (scoped relative to this package).
        // So first we call addClass({"java","lang"},"String") on the root package, then
        // addClass({"lang"}, "String"} on the java package, then
        // addClass({}, "String)" on the java.lang package.
        protected void addClass(Iterator<String> packageIdents, String name) {
            // If it's a sub-package, create it if necessary, then recurse:
            if (packageIdents.hasNext()) {
                String ident = packageIdents.next();
                PackageInfo subPkg = subPackages.get(ident);
                if (subPkg == null) {
                    subPkg = new PackageInfo();
                    subPackages.put(ident, subPkg);
                }
                subPkg.addClass(packageIdents, name);
            } else {
                // Lives in this package:
                types.put(name, null);
            }
        }

        /**
         * Gets the type for the given name from this package, either using cached copy
         * or by calculating it on demand.
         *
         * @param prefix The package name, ending in ".", e.g. "java.lang."
         * @param name   The unqualified type name, e.g. "String".
         */
        @OnThread(Tag.Worker)
        private AssistContentThreadSafe getType(String prefix, String name, JavadocResolver javadocResolver) {
            return types.computeIfAbsent(name, s -> {
                // To safely get an AssistContentThreadSafe, we must create one from the FXPlatform thread.
                // So we need to hop across to the FXPlatform thread.  Because we are an arbitrary background
                // worker thread, it is safe to use wait afterwards; without risk of deadlock:
                try {

                    Class<?> c = forName(prefix + s, reflections.getConfiguration().getClassLoaders());
                    CompletableFuture<AssistContentThreadSafe> f = new CompletableFuture<>();
                    Platform.runLater(() -> f.complete(new AssistContentThreadSafe(new ImportedTypeCompletion(c, javadocResolver))));
                    return f.get();
                } catch (ReflectionsException e) {
                    // Don't report this one; it happens frequently, for example
                    // when the user is typing in an import in a Stride import frame.
                    // We check j, ja, jav, ... java.ut, java.uti, etc.
                    // No need to report an exception for every bad import
                    return null;
                } catch (Exception e) {
                    Debug.reportError(e);
                    return null;
                }
            });
        }

        /**
         * Gets types arising from a given import directive in the source code.
         *
         * @param prefix The prefix of this package, ending in ".".  E.g. for the java
         *               package, we would be passed "java."
         * @param idents The next in the sequence of identifiers.  E.g. if we are the java package
         *               we might be passed {"lang", "String"}.  The final item may be an asterisk,
         *               e.g. {"lang", "*"}, in which case we return all types.  Otherwise we will
         *               return an empty list (if the type is not found), or a singleton list.
         * @return The
         */
        @OnThread(Tag.Worker)
        public List<AssistContentThreadSafe> getImportedTypes(String prefix, Iterator<String> idents, JavadocResolver javadocResolver) {
            if (!idents.hasNext())
                return Collections.emptyList();

            String s = idents.next();
            if (s.equals("*")) {
                // Return all types:

                // Take a copy in case it causes problems that getType modifies the collection
                Collection<String> typeNames = new ArrayList<>(types.keySet());
                return typeNames.stream().map(t -> getType(prefix, t, javadocResolver)).filter(ac -> ac != null).collect(Collectors.toList());
            } else if (idents.hasNext()) {
                // Still more identifiers to follow.  Look for package:
                if (subPackages.containsKey(s))
                    return subPackages.get(s).getImportedTypes(prefix + s + ".", idents, javadocResolver);
                else
                    return Collections.emptyList();
            } else {
                // Final identifier, not an asterisk, look for class:
                AssistContentThreadSafe ac = getType(prefix, s, javadocResolver);
                if (ac != null)
                    return Collections.singletonList(ac);
                else
                    return Collections.emptyList();
            }
        }

        public void addTypes(PackageInfo from) {
            types.putAll(from.types);
            from.subPackages.forEach((name, pkg) -> {
                subPackages.putIfAbsent(name, new PackageInfo());
                subPackages.get(name).addTypes(pkg);
            });
        }
    }

    // PackageInfo, but for the root type.
    private class RootPackageInfo extends PackageInfo {
        // Adds fully qualified class name to type list.
        public void addClass(String name) {
            String[] splitParts = name.split("\\.", -1);
            addClass(Arrays.asList(Arrays.copyOf(splitParts, splitParts.length - 1)).iterator(), splitParts[splitParts.length - 1]);
        }
    }

    @OnThread(Tag.Any)
    private CompletableFuture<? extends PackageInfo> getRoot() {
        synchronized (monitor) {
            // Already started calculating:
            if (root != null) {
                return root;
            } else {
                // Start calculating:
                root = new CompletableFuture<>();
                // We don't use runBackground because we don't want to end up
                // behind other callers of getRoot in the queue (this can
                // cause a deadlock because there are no background threads
                // available, as they are all blocked waiting for this
                // future to complete):
                new Thread() {
                    public void run() {
                        RootPackageInfo rootPkg = findAllTypes();
                        try {
                            loadCachedImports(rootPkg);
                        } finally {
                            root.complete(rootPkg);
                        }
                    }
                }.start();
                return root;
            }
        }
    }

    /**
     * Given an import source (e.g. "java.lang.String", "java.util.*"), finds all the
     * types that will be imported.
     * <p>
     * If the one-time on-load import scanning has not finished yet, this method will
     * wait until it has.  Hence you should call it from a worker thread, not from a
     * GUI thread where it could block the GUI for a long time.
     */
    @OnThread(Tag.Worker)
    public List<AssistContentThreadSafe> getImportedTypes(String importSrc, JavadocResolver javadocResolver) {
        try {
            return getRoot().get().getImportedTypes("", Arrays.asList(importSrc.split("\\.", -1)).iterator(), javadocResolver);
        } catch (InterruptedException | ExecutionException e) {
            Debug.reportError("Exception in getImportedTypes", e);
            return Collections.emptyList();
        }
    }

    // Gets the class loader config to pass to the Reflections library
    @OnThread(Tag.Worker)
    private ConfigurationBuilder getClassloaderConfig() {
        List<ClassLoader> classLoadersList = new ArrayList<ClassLoader>();
        classLoadersList.add(ClasspathHelper.contextClassLoader());
        classLoadersList.add(ClasspathHelper.staticClassLoader());
        try {
            CompletableFuture<ClassLoader> projectClassLoader = new CompletableFuture<>();
            // Safe to wait for platform thread because we are a worker thread:
            Platform.runLater(() -> {
                projectClassLoader.complete(project.getClassLoader());
            });
            classLoadersList.add(projectClassLoader.get());
        } catch (InterruptedException | ExecutionException e) {
            Debug.reportError(e);
        }

        Set<URL> urls = new HashSet<>();
        urls.addAll(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0])));
        urls.addAll(Arrays.asList(Boot.getInstance().getRuntimeUserClassPath()));
        // By default, rt.jar doesn't appear on the classpath, but it contains all the core classes:
        try {
            urls.add(Boot.getJREJar("rt.jar"));
        } catch (MalformedURLException e) {
            Debug.reportError(e);
        }

        // Stop jnilib files being processed on Mac:
        urls.removeIf(u -> u.toExternalForm().endsWith("jnilib") || u.toExternalForm().endsWith("zip"));

        urls.removeIf(u -> {
            if ("file".equals(u.getProtocol())) {
                try {
                    File f = new File(u.toURI());
                    if (f.getName().startsWith(".")) return true;
                    if (f.getName().endsWith(".so")) return true;
                } catch (URISyntaxException usexc) {
                }
            }
            return false;
        });

        //Debug.message("Class loader URLs:");
        //urls.stream().sorted(Comparator.comparing(URL::toString)).forEach(u -> Debug.message("  " + u));

        ClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]));

        return new ConfigurationBuilder()
                .setScanners(new SubTypesScanner(false /* don't exclude Object.class */))
                .setUrls(urls)
                .addClassLoader(cl)
                .useParallelExecutor();
    }

    /**
     * Creates a Reflections instance ready to do import scanning.
     *
     * @param importSrcs Currently not used by caller, but narrows imports down
     * @return
     */
    @OnThread(Tag.Worker)
    private Reflections getReflections(List<String> importSrcs) {
        FilterBuilder filter = new FilterBuilder();

        for (String importSrc : importSrcs) {
            if (importSrc.endsWith(".*")) {
                // Chop off star but keep the dot, then escape dots:
                String importSrcRegex = importSrc.substring(0, importSrc.length() - 1).replace(".", "\\.");
                filter = filter.include(importSrcRegex + ".*");
            } else {
                // Look for that exactly.  It seems we need .* because I think the library
                // uses the same filter to match files as classes, so an exact match will miss the class file:
                filter = filter.include(importSrc.replace(".", "\\.") + ".*");
            }
        }
        // Exclude $1, etc classes -- they cannot be used directly, and asking about them causes errors:
        filter = filter.exclude(".*\\$\\d.*");
        filter = filter.exclude("com\\.sun\\..*");

        try {
            return new Reflections(getClassloaderConfig()
                    .filterInputsBy(filter)
            );
        } catch (Throwable e) {
            Debug.reportError(e);
            return null;
        }
    }

    /**
     * Gets a package-tree structure which includes all packages and class-names
     * on the current class-path (by scanning all JARs and class-files on the path).
     *
     * @return A package-tree structure with all class names present, but not any further
     * details about the classes.
     */
    @OnThread(Tag.Worker)
    private RootPackageInfo findAllTypes() {
        reflections = getReflections(Collections.emptyList());

        if (reflections == null)
            return new RootPackageInfo();

        Set<String> classes;
        try {
            classes = getSubTypeNamesOf(reflections, Object.class);
        } catch (Throwable t) {
            Debug.reportError(t);
            classes = new HashSet<>();
        }

        // Also add Object itself, not found by default:
        classes.add(Object.class.getName());

        RootPackageInfo r = new RootPackageInfo();
        classes.forEach(r::addClass);
        return r;
    }

    /**
     * Starts scanning for available importable types from the classpath.
     * Will operate in a background thread.
     */
    public void startScanning() {
        // This will make sure the future has started:
        getRoot();
    }

    /**
     * Saves all java.** type information to a cache
     */
    public void saveCachedImports() {
        if (getRoot().isDone()) {
            Element cache = new Element("packages");
            cache.addAttribute(new Attribute("javaHome", getJavaHome()));
            cache.addAttribute(new Attribute("version", getVersion()));
            try {
                PackageInfo javaPkg = getRoot().get().subPackages.get("java");
                if (javaPkg != null) {
                    cache.appendChild(toXML(javaPkg, "java"));
                    FileOutputStream os = new FileOutputStream(getImportCachePath());
                    Utility.serialiseCodeTo(cache, os);
                    os.close();
                }
            } catch (InterruptedException | ExecutionException | IOException e) {
                Debug.reportError(e);
            }

        }
    }

    /**
     * Version of the currently running software
     */
    private static String getVersion() {
        return Config.isGreenfoot() ? Boot.GREENFOOT_VERSION : Boot.BLUEJ_VERSION;
    }

    /**
     * Java home directory
     */
    private static String getJavaHome() {
        return Boot.getInstance().getJavaHome().getAbsolutePath();
    }

    /**
     * Import cache path to save to/load from
     */
    private static File getImportCachePath() {
        return new File(Config.getUserConfigDir(), "import-cache.xml");
    }

    /**
     * Loads cached (java.**) imports into the given root package, if possible.
     */
    public void loadCachedImports(PackageInfo rootPkg) {
        try {
            Document xml = new Builder().build(getImportCachePath());
            Element packagesEl = xml.getRootElement();
            if (!packagesEl.getLocalName().equals("packages"))
                return;
            // If they've changed JDK or BlueJ/Greenfoot version, ignore the cache
            // (and thus generate fresh data later on):
            if (!getJavaHome().equals(packagesEl.getAttributeValue("javaHome")) || !getVersion().equals(packagesEl.getAttributeValue("version")))
                return;
            for (int i = 0; i < packagesEl.getChildElements().size(); i++) {
                fromXML(packagesEl.getChildElements().get(i), rootPkg);
            }
        } catch (ParsingException | IOException e) {
            Debug.message(e.getClass().getName() + " while reading import cache: " + e.getMessage());
        }
    }

    /**
     * Loads the given XML package item and puts it into the given parent package.
     */
    private void fromXML(Element pkgEl, PackageInfo addToParent) {
        String name = pkgEl.getAttributeValue("name");
        if (name == null)
            return;
        PackageInfo loadPkg = new PackageInfo();

        for (int i = 0; i < pkgEl.getChildElements().size(); i++) {
            Element el = pkgEl.getChildElements().get(i);
            if (el.getLocalName().equals("package")) {
                fromXML(el, loadPkg);
            } else {
                AssistContentThreadSafe acts = new AssistContentThreadSafe(el);
                String nameWithoutPackage = (acts.getDeclaringClass() == null ? "" : acts.getDeclaringClass() + "$") + acts.getName();
                loadPkg.types.put(nameWithoutPackage, acts);
            }
        }

        // Only store if successful:
        addToParent.subPackages.putIfAbsent(name, new PackageInfo());
        addToParent.subPackages.get(name).addTypes(loadPkg);
    }

    /**
     * Save the given PackageInfo item (with package name) to XML
     */
    private static Element toXML(PackageInfo pkg, String name) {
        Element el = new Element("package");
        el.addAttribute(new Attribute("name", name));
        pkg.types.values().forEach(acts -> {
            if (acts != null) el.appendChild(acts.toXML());
        });
        pkg.subPackages.forEach((subName, subPkg) -> el.appendChild(toXML(subPkg, subName)));
        return el;
    }

    private static <T> Set<String> getSubTypeNamesOf(Reflections reflections, final Class<T> type) {
        if (reflections.getStore().get(SubTypesScanner.class + "") == null) {
            throw new ReflectionsException(SubTypesScanner.class + " not configured.");
        }
        Set<String> subTypes = getSubTypesOf(reflections.getStore(), type.getName());
        return Sets.newHashSet(subTypes);
    }

    private static Set<String> getSubTypesOf(Store store, final String type) {
        Set<String> subTypes = Sets.newHashSet(store.get(SubTypesScanner.class + "", type));
        Set<String> result = new HashSet<>(subTypes);

        for (String subType : subTypes) {
            result.addAll(getSubTypesOf(store, subType));
        }
        return result;
    }
}
