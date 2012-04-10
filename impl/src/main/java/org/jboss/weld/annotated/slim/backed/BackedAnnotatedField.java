package org.jboss.weld.annotated.slim.backed;

import static org.jboss.weld.logging.messages.BeanMessage.PROXY_REQUIRED;
import static org.jboss.weld.util.reflection.Reflections.cast;

import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Set;

import javax.enterprise.inject.spi.AnnotatedField;

import org.jboss.weld.Container;
import org.jboss.weld.exceptions.InvalidObjectException;
import org.jboss.weld.resources.MemberTransformer;
import org.jboss.weld.serialization.FieldHolder;
import org.jboss.weld.util.reflection.Formats;

import com.google.common.collect.ImmutableSet;

public class BackedAnnotatedField<X> extends BackedAnnotatedMember<X> implements AnnotatedField<X>, Serializable {

    public static <X, Y extends X> AnnotatedField<X> of(Field field, BackedAnnotatedType<Y> declaringType) {
        BackedAnnotatedType<X> downcastDeclaringType = cast(declaringType);
        return new BackedAnnotatedField<X>(field.getGenericType(), field, downcastDeclaringType);
    }

    private final Field field;

    public BackedAnnotatedField(Type baseType, Field field, BackedAnnotatedType<X> declaringType) {
        super(baseType, declaringType);
        this.field = field;
    }

    public Field getJavaMember() {
        return field;
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        return field.getAnnotation(annotationType);
    }

    public Set<Annotation> getAnnotations() {
        return ImmutableSet.copyOf(field.getAnnotations());
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return field.isAnnotationPresent(annotationType);
    }

    @Override
    public String toString() {
        return Formats.formatAnnotatedField(this);
    }

    // Serialization

    private Object writeReplace() throws ObjectStreamException {
        return new SerializationProxy(field);
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException(PROXY_REQUIRED);
    }

    private static class SerializationProxy implements Serializable {

        private static final long serialVersionUID = -8041111397369568219L;
        private final FieldHolder field;

        public SerializationProxy(Field field) {
            this.field = new FieldHolder(field);
        }

        private Object readResolve() {
            return Container.instance().services().get(MemberTransformer.class).loadBackedMember(field.get());
        }
    }
}
