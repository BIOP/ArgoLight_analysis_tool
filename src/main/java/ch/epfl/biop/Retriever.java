package ch.epfl.biop;

import ch.epfl.biop.senders.Sender;
import ij.ImagePlus;

public interface Retriever {
    ImagePlus getImage(int index);
    int getNImages();
    Sender createSender();
    String getTarget();
}
