package org.autojs.fixture.r8compiler.g7;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public final class G7SerializableState implements Serializable {
    private static final long serialVersionUID = 713884065303271L;

    private String label;
    private int count;

    public G7SerializableState(String label, int count) {
        this.label = label;
        this.count = count;
    }

    public String getLabel() {
        return label;
    }

    public int getCount() {
        return count;
    }

    private void writeObject(ObjectOutputStream output) throws IOException {
        output.defaultWriteObject();
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
    }

    private Object readResolve() {
        return this;
    }
}
