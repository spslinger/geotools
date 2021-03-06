/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2010, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.geopkg.mosaic;

import it.geosolutions.jaiext.mosaic.MosaicRIF;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.operator.MosaicDescriptor;
import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.geopkg.GeoPackage;
import org.geotools.geopkg.Tile;
import org.geotools.geopkg.TileEntry;
import org.geotools.geopkg.TileMatrix;
import org.geotools.geopkg.TileReader;
import org.geotools.image.ImageWorker;
import org.geotools.referencing.CRS;
import org.geotools.util.Utilities;
import org.geotools.util.factory.GeoTools;
import org.geotools.util.factory.Hints;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Envelope;
import org.opengis.coverage.grid.Format;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValue;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

/**
 * GeoPackage Grid Reader (supports the GP mosaic datastore).
 *
 * @author Justin Deoliveira
 * @author Niels Charlier
 */
public class GeoPackageReader extends AbstractGridCoverage2DReader {

    /** The {@link Logger} for this {@link GeoPackageReader}. */
    private static final Logger LOGGER = Logging.getLogger(GeoPackageReader.class);

    protected static final int DEFAULT_TILE_SIZE = 256;

    protected static final int ZOOM_LEVEL_BASE = 2;

    protected GridCoverageFactory coverageFactory;

    protected File sourceFile;

    protected Map<String, TileEntry> tiles = new LinkedHashMap<String, TileEntry>();

    GeoPackage file;

    public GeoPackageReader(Object source, Hints hints) throws IOException {
        coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints);

        sourceFile = GeoPackageFormat.getFileFromSource(source);
        file = new GeoPackage(sourceFile, null, null, true);
        for (TileEntry tile : file.tiles()) {
            tiles.put(tile.getTableName(), tile);
        }

        // have a sane default when hit with no name, useful in particular
        // when the geopackage only has one coverage
        coverageName = tiles.keySet().iterator().next();
    }

    @Override
    public Format getFormat() {
        return new GeoPackageFormat();
    }

    @Override
    protected boolean checkName(String coverageName) {
        Utilities.ensureNonNull("coverageName", coverageName);
        return tiles.keySet().contains(coverageName);
    }

    @Override
    public GeneralEnvelope getOriginalEnvelope(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException(
                    "The specified coverageName " + coverageName + "is not supported");
        }

        return new GeneralEnvelope(tiles.get(coverageName).getTileMatrixSetBounds());
    }

    @Override
    protected double[] getHighestRes(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException(
                    "The specified coverageName " + coverageName + "is not supported");
        }

        List<TileMatrix> matrices = tiles.get(coverageName).getTileMatricies();
        TileMatrix matrix = matrices.get(matrices.size() - 1);
        return new double[] {matrix.getXPixelSize(), matrix.getYPixelSize()};
    }

    @Override
    public GridEnvelope getOriginalGridRange(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException(
                    "The specified coverageName " + coverageName + "is not supported");
        }

        List<TileMatrix> matrices = tiles.get(coverageName).getTileMatricies();
        TileMatrix matrix = matrices.get(matrices.size() - 1);
        return new GridEnvelope2D(
                new Rectangle(
                        matrix.getMatrixWidth() * matrix.getTileWidth(),
                        matrix.getMatrixHeight() * matrix.getTileHeight()));
    }

    @Override
    public CoordinateReferenceSystem getCoordinateReferenceSystem(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException(
                    "The specified coverageName " + coverageName + "is not supported");
        }

        try {
            return CRS.decode("EPSG:" + tiles.get(coverageName).getSrid(), true);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String[] getGridCoverageNames() {
        return tiles.keySet().toArray(new String[tiles.size()]);
    }

    @Override
    public int getGridCoverageCount() {
        return tiles.size();
    }

    @Override
    public GridCoverage2D read(String coverageName, GeneralParameterValue[] parameters)
            throws IllegalArgumentException, IOException {
        TileEntry entry = tiles.get(coverageName);
        RenderedImage image = null;
        ReferencedEnvelope resultEnvelope = null;

        CoordinateReferenceSystem crs = getCoordinateReferenceSystem(coverageName);

        ReferencedEnvelope requestedEnvelope = null;
        Rectangle dim = null;

        if (parameters != null) {
            for (int i = 0; i < parameters.length; i++) {
                final ParameterValue param = (ParameterValue) parameters[i];
                final ReferenceIdentifier name = param.getDescriptor().getName();
                if (name.equals(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName())) {
                    final GridGeometry2D gg = (GridGeometry2D) param.getValue();
                    try {
                        requestedEnvelope =
                                ReferencedEnvelope.create(
                                                gg.getEnvelope(), gg.getCoordinateReferenceSystem())
                                        .transform(crs, true);
                        ;
                    } catch (Exception e) {
                        requestedEnvelope = null;
                    }

                    dim = gg.getGridRange2D().getBounds();
                    continue;
                }
            }
        }

        // find the closest zoom based on horizontal resolution
        TileMatrix bestMatrix = null;
        if (requestedEnvelope != null && dim != null) {
            // requested res
            double horRes =
                    requestedEnvelope.getSpan(0)
                            / dim.getWidth(); // proportion of total width that is being
            // requested

            // loop over matrices
            double difference = Double.MAX_VALUE;
            for (TileMatrix matrix : entry.getTileMatricies()) {
                if (!matrix.hasTiles()) {
                    continue;
                }
                double newRes = matrix.getXPixelSize();
                double newDifference = Math.abs(horRes - newRes);
                if (newDifference < difference) {
                    difference = newDifference;
                    bestMatrix = matrix;
                }
            }
        }
        if (bestMatrix == null) {
            // pick the highest resolution, like in a geotiff with overviews
            double resolution = Double.POSITIVE_INFINITY;
            for (TileMatrix matrix : entry.getTileMatricies()) {
                if (!matrix.hasTiles()) {
                    continue;
                }
                double newRes = matrix.getXPixelSize();
                if (newRes < resolution) {
                    resolution = newRes;
                    bestMatrix = matrix;
                }
            }
        }

        if (bestMatrix == null) {
            // it means no level has tiles, return null, it's ok to do so
            return null;
        }

        Envelope entryBounds = entry.getTileMatrixSetBounds();
        double resX = bestMatrix.getXPixelSize() * bestMatrix.getTileWidth();
        double resY = bestMatrix.getYPixelSize() * bestMatrix.getTileHeight();
        /*
         * From the specification: "The tile coordinate (0,0) always refers to the tile in the upper left corner of the tile matrix at any zoom
         * level, regardless of the actual availability of that tile."
         * So remember the y axis goes from top to bottom, not the other way around
         */
        double offsetX = entryBounds.getMinX();
        double offsetY = entryBounds.getMaxY();

        // crop tiles to requested envelope if necessary
        int leftTile, bottomTile, rightTile, topTile;
        if (requestedEnvelope != null) {
            TileBoundsCalculator tileBoundsCalculator =
                    new TileBoundsCalculator(requestedEnvelope, resX, resY, offsetX, offsetY)
                            .invoke();
            leftTile = tileBoundsCalculator.getLeftTile();
            bottomTile = tileBoundsCalculator.getBottomTile();
            rightTile = tileBoundsCalculator.getRightTile();
            topTile = tileBoundsCalculator.getTopTile();
        } else {
            TileBoundsCalculator tileBoundsCalculator =
                    new TileBoundsCalculator(entryBounds, resX, resY, offsetX, offsetY).invoke();
            leftTile = tileBoundsCalculator.getLeftTile();
            bottomTile = tileBoundsCalculator.getBottomTile();
            rightTile = tileBoundsCalculator.getRightTile();
            topTile = tileBoundsCalculator.getTopTile();

            final double minX = entryBounds.getMinX();
            final double maxX = entryBounds.getMaxX();
            final double minY = entryBounds.getMinY();
            final double maxY = entryBounds.getMaxY();

            // cannot "round" here or half a tile in the requested area might be missing
            // TODO: the code could consider if the eventual extra tile introduced by
            // floor/ceil actually contributes at least one full pixel to the output, or
            // a significant part of it
            leftTile = (int) Math.floor((minX - offsetX) / resX);
            topTile = (int) Math.floor((offsetY - maxY) / resY);
            rightTile = (int) Math.ceil((maxX - offsetX) / resX);
            bottomTile = (int) Math.ceil((offsetY - minY) / resY);
        }

        try (TileReader it =
                file.reader(
                        entry,
                        bestMatrix.getZoomLevel(),
                        bestMatrix.getZoomLevel(),
                        leftTile,
                        rightTile,
                        topTile,
                        bottomTile)) {
            /**
             * Composing the output is harder than it seems, GeoPackage does not mandate any
             * uniformity in tiles, they can be in different formats (a mix of PNG and JPEG) and can
             * have different color models, thus a mix of (possibly different) palettes, gray, RGB,
             * RGBA. GDAL in particular defaults to generate a mix of PNG and JPEG to generate the
             * slow and large PNG format only when transparency is actually needed
             */
            List<RenderedImage> sources = new ArrayList<>();
            ImageWorker iw = new ImageWorker();
            TileImageReader tileReader = new TileImageReader();

            while (it.hasNext()) {
                Tile tile = it.next();
                // recalculate the envelope we are actually returning (remember y axis is flipped)
                ReferencedEnvelope tileEnvelope =
                        new ReferencedEnvelope( //
                                offsetX + tile.getColumn() * resX, //
                                offsetX + (tile.getColumn() + 1) * resX, //
                                offsetY - (tile.getRow() + 1) * resY, //
                                offsetY - tile.getRow() * resY,
                                crs);
                if (resultEnvelope == null) {
                    resultEnvelope = tileEnvelope;
                } else {
                    resultEnvelope.expandToInclude(tileEnvelope);
                }

                BufferedImage tileImage = tileReader.read(tile.getData());

                iw.setImage(tileImage);
                int posx = (tile.getColumn() - leftTile) * DEFAULT_TILE_SIZE;
                int posy = (tile.getRow() - topTile) * DEFAULT_TILE_SIZE;
                if (posx != 0 || posy != 0) {
                    iw.translate(
                            posx, posy, Interpolation.getInstance(Interpolation.INTERP_NEAREST));
                    RenderedImage translated = iw.getRenderedImage();
                    sources.add(translated);
                } else {
                    sources.add(tileImage);
                }
            }
            it.close();

            if (sources.isEmpty()) {
                // no tiles
                return null;
            } else if (sources.size() == 1) {
                // one tile
                image = sources.get(0);
            } else {
                // at the time of writing, only JAI-EXT mosaic can handle a mix of different
                // color models, we need to use it explicitly
                final ParameterBlockJAI pb =
                        new ParameterBlockJAI(new it.geosolutions.jaiext.mosaic.MosaicDescriptor());
                sources.forEach(s -> pb.addSource(s));
                pb.setParameter("mosaicType", MosaicDescriptor.MOSAIC_TYPE_OVERLAY);
                pb.setParameter("sourceAlpha", null);
                pb.setParameter("sourceROI", null);
                pb.setParameter("sourceThreshold", null);
                pb.setParameter("backgroundValues", new double[] {0});
                pb.setParameter("nodata", null);

                RenderingHints hints = new Hints(JAI.getDefaultInstance().getRenderingHints());
                hints.putAll(GeoTools.getDefaultHints());
                image = new MosaicRIF().create(pb, hints);
            }
        }
        return coverageFactory.create(entry.getTableName(), image, resultEnvelope);
    }

    private int normalizeTile(int tile, int min, int max) {
        if (tile < min) {
            return min;
        } else if (tile > max) {
            return max;
        } else {
            return tile;
        }
    }

    protected BufferedImage getStartImage(BufferedImage copyFrom, int width, int height) {
        Map<String, Object> properties = null;

        if (copyFrom.getPropertyNames() != null) {
            properties = new HashMap<String, Object>();
            for (String name : copyFrom.getPropertyNames()) {
                properties.put(name, copyFrom.getProperty(name));
            }
        }

        SampleModel sm = copyFrom.getSampleModel().createCompatibleSampleModel(width, height);
        WritableRaster raster = Raster.createWritableRaster(sm, null);

        BufferedImage image =
                new BufferedImage(
                        copyFrom.getColorModel(),
                        raster,
                        copyFrom.isAlphaPremultiplied(),
                        (Hashtable<?, ?>) properties);

        // white background
        Graphics2D g2D = (Graphics2D) image.getGraphics();
        Color save = g2D.getColor();
        g2D.setColor(Color.WHITE);
        g2D.fillRect(0, 0, image.getWidth(), image.getHeight());
        g2D.setColor(save);

        return image;
    }

    protected BufferedImage getStartImage(int imageType, int width, int height) {
        if (imageType == BufferedImage.TYPE_CUSTOM) imageType = BufferedImage.TYPE_3BYTE_BGR;

        BufferedImage image = new BufferedImage(width, height, imageType);

        // white background
        Graphics2D g2D = (Graphics2D) image.getGraphics();
        Color save = g2D.getColor();
        g2D.setColor(Color.WHITE);
        g2D.fillRect(0, 0, image.getWidth(), image.getHeight());
        g2D.setColor(save);

        return image;
    }

    protected BufferedImage getStartImage(int width, int height) {
        return getStartImage(BufferedImage.TYPE_CUSTOM, width, height);
    }

    @Override
    public GridCoverage2D read(GeneralParameterValue[] parameters)
            throws IllegalArgumentException, IOException {
        return read(coverageName, parameters);
    }

    @Override
    public void dispose() {
        if (file != null) {
            file.close();
        }
    }

    /** Method object returning 4 separate params */
    private class TileBoundsCalculator {
        private Envelope requestedEnvelope;
        private double resX;
        private double resY;
        private double offsetX;
        private double offsetY;
        private int leftTile;
        private int bottomTile;
        private int rightTile;
        private int topTile;

        public TileBoundsCalculator(
                Envelope requestedEnvelope,
                double resX,
                double resY,
                double offsetX,
                double offsetY) {
            this.requestedEnvelope = requestedEnvelope;
            this.resX = resX;
            this.resY = resY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        public int getLeftTile() {
            return leftTile;
        }

        public int getBottomTile() {
            return bottomTile;
        }

        public int getRightTile() {
            return rightTile;
        }

        public int getTopTile() {
            return topTile;
        }

        public TileBoundsCalculator invoke() {
            // the requested bounds
            final double minX = requestedEnvelope.getMinX();
            final double maxX = requestedEnvelope.getMaxX();
            final double minY = requestedEnvelope.getMinY();
            final double maxY = requestedEnvelope.getMaxY();

            // cannot "round" here or half a tile in the requested area might be missing
            leftTile = (int) Math.floor((minX - offsetX) / resX);
            topTile = (int) Math.floor((offsetY - maxY) / resY);
            rightTile = (int) Math.ceil((maxX - offsetX) / resX);
            // but check if the extra tile is completely outside, and if so, remove
            if (offsetX + (rightTile * resX) > maxX) {
                rightTile -= 1;
            }
            bottomTile = (int) Math.ceil((offsetY - minY) / resY);
            if (offsetY - (bottomTile * resY) < minY) {
                bottomTile -= 1;
            }
            return this;
        }
    }
}
