package org.jboss.weld.environment.se.discovery;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.enterprise.inject.Vetoed;
import javax.inject.Inject;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;
import org.jboss.weld.environment.se.logging.WeldSELogger;
import org.jboss.weld.environment.se.util.SEReflections;
import org.jboss.weld.resources.spi.ClassFileInfo;

import com.google.common.cache.LoadingCache;

/**
 * A store and the access point to the main information about the class that was indexed by jandex in Weld SE.
 *
 * @author Martin Kouba
 * @author Matej Briškár
 */
public class WeldSEClassFileInfo implements ClassFileInfo {

    private static final DotName DOT_NAME_INJECT = DotName.createSimple(Inject.class.getName());

    private static final DotName DOT_NAME_VETOED = DotName.createSimple(Vetoed.class.getName());

    private static final DotName OBJECT_NAME = DotName.createSimple(Object.class.getName());

    private static final String CONSTRUCTOR_METHOD_NAME = "<init>";

    private static final String PACKAGE_INFO_NAME = "package-info";

    private final ClassInfo classInfo;

    private final IndexView index;

    private final boolean isVetoed;

    private final boolean hasCdiConstructor;

    private final LoadingCache<DotName, Set<String>> annotationClassAnnotationsCache;

    private final ClassLoader classLoader;

    private static final Logger log = Logger.getLogger(WeldSEClassFileInfo.class);

    public WeldSEClassFileInfo(String className, IndexView index, LoadingCache<DotName, Set<String>> annotationClassAnnotationsCache, ClassLoader classLoader) {
        this.index = index;
        this.annotationClassAnnotationsCache = annotationClassAnnotationsCache;
        this.classInfo = index.getClassByName(DotName.createSimple(className));
        if (this.classInfo == null) {
            throw new IllegalStateException("Index for name: " + className + " not found");
        }
        this.isVetoed = isVetoedTypeOrPackage();
        this.hasCdiConstructor = this.classInfo.hasNoArgsConstructor() || hasInjectConstructor();
        this.classLoader = classLoader;
    }

    @Override
    public String getClassName() {
        return classInfo.name().toString();
    }

    @Override
    public boolean isAnnotationDeclared(Class<? extends Annotation> annotation) {
        return isAnnotationDeclared(classInfo, annotation);
    }

    @Override
    public boolean containsAnnotation(Class<? extends Annotation> annotation) {
        return containsAnnotation(classInfo, DotName.createSimple(annotation.getName()), annotation);
    }

    @Override
    public int getModifiers() {
        return classInfo.flags();
    }

    @Override
    public boolean hasCdiConstructor() {
        return hasCdiConstructor;
    }

    @Override
    public boolean isAssignableFrom(Class<?> fromClass) {
        return isAssignableFrom(getClassName(), fromClass);
    }

    @Override
    public boolean isAssignableTo(Class<?> toClass) {
        return isAssignableTo(classInfo.name(), toClass);
    }

    @Override
    public boolean isVetoed() {
        return isVetoed;
    }

    @Override
    public boolean isTopLevelClass() {
        // TODO This is not portable per the JSL
        // TODO Modify jandex to contain isTopLevelClass attribute
        return !classInfo.name().local().contains("$");
    }

    @Override
    public String getSuperclassName() {
        return classInfo.superName().toString();
    }

    private boolean isVetoedTypeOrPackage() {

        if (isAnnotationDeclared(classInfo, DOT_NAME_VETOED)) {
            return true;
        }

        final DotName packageInfoName = DotName.createComponentized(getPackageName(classInfo.name()), PACKAGE_INFO_NAME);
        ClassInfo packageInfo = index.getClassByName(packageInfoName);

        if (packageInfo != null && isAnnotationDeclared(packageInfo, DOT_NAME_VETOED)) {
            return true;
        }
        return false;
    }

    private boolean isAnnotationDeclared(ClassInfo classInfo, Class<? extends Annotation> annotation) {
        return isAnnotationDeclared(classInfo, DotName.createSimple(annotation.getName()));
    }

    private boolean isAnnotationDeclared(ClassInfo classInfo, DotName requiredAnnotationName) {
        Map<DotName, List<AnnotationInstance>> annotationsMap = classInfo.annotations();
        List<AnnotationInstance> annotations = annotationsMap.get(requiredAnnotationName);
        if (annotations != null) {
            for (AnnotationInstance annotationInstance : annotations) {
                if (annotationInstance.target().equals(classInfo)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasInjectConstructor() {
        List<AnnotationInstance> annotationInstances = classInfo.annotations().get(DOT_NAME_INJECT);
        if (annotationInstances != null) {
            for (AnnotationInstance instance : annotationInstances) {
                AnnotationTarget target = instance.target();
                if (target instanceof MethodInfo) {
                    MethodInfo methodInfo = (MethodInfo) target;
                    if (methodInfo.name().equals(CONSTRUCTOR_METHOD_NAME)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private DotName getPackageName(DotName name) {
        if (name.isComponentized()) {
            return name.prefix();
        } else {
            final int lastIndex = name.local().lastIndexOf(".");
            if (lastIndex == -1) {
                return name;
            }
            return DotName.createSimple(name.local().substring(0, lastIndex));
        }
    }

    /**
     * @param className
     * @param fromClass
     * @return
     */
    private boolean isAssignableFrom(String className, Class<?> fromClass) {
        if (className.equals(fromClass.getName())) {
            return true;
        }
        if (Object.class.equals(fromClass)) {
            return false; // there's nothing assignable from Object.class except for Object.class
        }

        Class<?> superClass = fromClass.getSuperclass();

        if (superClass != null && isAssignableFrom(className, superClass)) {
            return true;
        }

        for (Class<?> interfaceClass : fromClass.getInterfaces()) {
            if (isAssignableFrom(className, interfaceClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param to
     * @param name
     * @return <code>true</code> if the name is equal to the fromName, or if the name represents a superclass or superinterface of the fromName,
     *         <code>false</code> otherwise
     */
    private boolean isAssignableTo(DotName name, Class<?> to) {
        if (to.getName().equals(name.toString())) {
            return true;
        }
        if (OBJECT_NAME.equals(name)) {
            return false; // there's nothing assignable from Object.class except for Object.class
        }

        ClassInfo fromClassInfo = index.getClassByName(name);
        if (fromClassInfo == null) {
            // We reached a class that is not in the index. Let's use reflection.
            final Class<?> clazz = loadClass(name.toString());
            return to.isAssignableFrom(clazz);
        }

        DotName superName = fromClassInfo.superName();

        if (superName != null && isAssignableTo(superName, to)) {
            return true;
        }

        if (fromClassInfo.interfaces() != null) {
            for (DotName interfaceName : fromClassInfo.interfaces()) {
                if (isAssignableTo(interfaceName, to)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAnnotation(ClassInfo classInfo, DotName requiredAnnotationName, Class<? extends Annotation> requiredAnnotation) {
        // Type and members
        if (classInfo.annotations().containsKey(requiredAnnotationName)) {
            return true;
        }
        // Meta-annotations
        for (DotName annotation : classInfo.annotations().keySet()) {
            try {
                if (annotationClassAnnotationsCache.get(annotation).contains(requiredAnnotationName.toString())) {
                    return true;
                }
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        // Superclass
        final DotName superName = classInfo.superName();

        if (superName != null && !OBJECT_NAME.equals(superName)) {
            final ClassInfo superClassInfo = index.getClassByName(superName);
            if (superClassInfo == null) {
                // we are accessing a class that is outside of the jandex index
                // fallback to using reflection
                return SEReflections.containsAnnotation(loadClass(superName.toString()), requiredAnnotation);
            }
            if (containsAnnotation(superClassInfo, requiredAnnotationName, requiredAnnotation)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> loadClass(String className) {
        log.trace("Loading class with class loader: " + className);
        Class<?> clazz = null;
        try {
            clazz = classLoader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            throw WeldSELogger.LOG.unableToLoadClass(className);
        }
        return clazz;
    }

    @Override
    public String toString() {
        return classInfo.toString();
    }
}

