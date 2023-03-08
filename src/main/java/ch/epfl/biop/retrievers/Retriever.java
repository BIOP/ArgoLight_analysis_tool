package ch.epfl.biop.retrievers;

import ij.ImagePlus;

public interface Retriever {
    ImagePlus getImage(int index);
    int getNImages();
    String getParentTarget();
    boolean isProcessingAllRawImages();
}
