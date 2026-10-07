package com.creatorcrm.contacts;

import java.io.IOException;

/** Reads the text in a picture on this computer, for free. */
public interface ImageOcr {

    /** Null when this computer can read pictures; otherwise why not, in plain words. */
    String unavailable();

    /** The text in the picture, line by line. {@code ext} is the file type ("png", "jpg"). */
    String read(byte[] image, String ext) throws IOException;
}
