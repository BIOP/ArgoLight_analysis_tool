# Metrology using ArgoLight Slides

A Fiji plugin measuring objective-related metrics on images using the OMERO database.

A grid of rings from ArgoLight LM slides is imaged with different objectives, at different zoom factor.
Images are first uploaded on OMERO. Each image is processed locally ; three metrics are computed on each ring : 
field uniformity, field distortion and full width at half maximum. Results are finally sent back to OMERO as key-value pairs,
tags and OMERO.table.

## Installation
- Activate the update-site OMERO 5.5-5.6
- Download the latest release and copy the two .jar files in the Plugin folder of Fiji.
- Restart Fiji.

