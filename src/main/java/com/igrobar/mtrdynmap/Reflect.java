package com.igrobar.mtrdynmap;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflection. MTR is only reached through reflection, so it is not needed at build time.
 *
 * On hybrid servers such as Youer every method/field lookup made by a plugin goes through the
 * server remapper and is expensive, so each lookup is done once per (class, name, argument types).
 */
final class Reflect {
    private static final Object MISSING = new Object();

    private static final Map<Key, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<Key, Object> FIELDS = new ConcurrentHashMap<>();

    private record Key(Class<?> owner, String name, List<Class<?>> args) {
    }

    private Reflect() {
    }

    static void clear() {
        METHODS.clear();
        FIELDS.clear();
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            return null;
        }
        Method method = method(target.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        }
        return method.invoke(target, args);
    }

    static Object optionalCall(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Value of a field (public or declared in the class or a superclass), or null if there is no such field. */
    static Object fieldValue(Object target, String name) throws Exception {
        if (target == null) {
            return null;
        }
        Field field = field(target.getClass(), name);
        return field == null ? null : field.get(target);
    }

    private static Field field(Class<?> owner, String name) {
        Key key = new Key(owner, name, List.of());
        Object cached = FIELDS.get(key);
        if (cached == null) {
            cached = MISSING;
            try {
                cached = owner.getField(name);
            } catch (NoSuchFieldException | RuntimeException notPublic) {
                for (Class<?> type = owner; type != null && cached == MISSING; type = type.getSuperclass()) {
                    try {
                        Field declared = type.getDeclaredField(name);
                        declared.setAccessible(true);
                        cached = declared;
                    } catch (NoSuchFieldException | RuntimeException ignored) {
                        // not declared here or not accessible, keep looking
                    }
                }
            }
            FIELDS.put(key, cached);
        }
        return cached == MISSING ? null : (Field) cached;
    }

    static Method method(Class<?> owner, String name, Object[] args) {
        Key key = new Key(owner, name, argTypes(args));
        Object cached = METHODS.get(key);
        if (cached == null) {
            Method found = findPublicMethod(owner, name, args);
            if (found == null) {
                found = findAnyPublicMethod(owner, name, args);
            }
            cached = found == null ? MISSING : found;
            METHODS.put(key, cached);
        }
        return cached == MISSING ? null : (Method) cached;
    }

    /** Interfaces first, then public methods of public classes: this order works on hybrid servers. */
    private static Method findPublicMethod(Class<?> owner, String name, Object[] args) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                Method method = findInInterface(iface, name, args);
                if (method != null) {
                    return method;
                }
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && matches(method, name, args)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findInInterface(Class<?> iface, String name, Object[] args) {
        for (Method method : iface.getMethods()) {
            if (matches(method, name, args)) {
                return method;
            }
        }
        for (Class<?> parent : iface.getInterfaces()) {
            Method method = findInInterface(parent, name, args);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method findAnyPublicMethod(Class<?> owner, String name, Object[] args) {
        for (Method method : owner.getMethods()) {
            if (!matches(method, name, args)) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
                // invoke() reports the problem if access is really denied
            }
            return method;
        }
        return null;
    }

    private static List<Class<?>> argTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        return Arrays.asList(types);
    }

    private static boolean matches(Method method, String name, Object[] args) {
        if (method.getParameterCount() != args.length || !method.getName().equals(name)) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (args[i] != null && !wrap(params[i]).isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }

    static Throwable unwrap(Throwable throwable) {
        while (throwable instanceof InvocationTargetException ite && ite.getCause() != null) {
            throwable = ite.getCause();
        }
        return throwable;
    }
}
