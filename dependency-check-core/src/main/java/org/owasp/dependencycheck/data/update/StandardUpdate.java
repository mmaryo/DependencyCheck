/*
 * This file is part of dependency-check-core.
 *
 * Dependency-check-core is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Dependency-check-core is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * dependency-check-core. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update;

import org.owasp.dependencycheck.data.nvdcve.InvalidDataException;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.owasp.dependencycheck.data.UpdateException;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.Settings;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.utils.InvalidSettingException;
import static org.owasp.dependencycheck.data.update.DataStoreMetaInfo.MODIFIED;
import org.owasp.dependencycheck.utils.FileUtils;

/**
 * Class responsible for updating the NVDCVE data store.
 *
 * @author Jeremy Long (jeremy.long@owasp.org)
 */
public class StandardUpdate {

    /**
     * The max thread pool size to use when downloading files.
     */
    public static final int MAX_THREAD_POOL_SIZE = Settings.getInt(Settings.KEYS.MAX_DOWNLOAD_THREAD_POOL_SIZE, 3);
    /**
     * Information about the timestamps and URLs for data that needs to be
     * updated.
     */
    private DataStoreMetaInfo properties;
    /**
     * A collection of updateable NVD CVE items.
     */
    private Updateable updateable;
    /**
     * A flag indicating whether or not the current data store should be
     * deleted.
     */
    private boolean deleteAndRecreate = false;
    /**
     * Reference to the Cve Database.
     */
    private CveDB cveDB = null;

    /**
     * Gets whether or not an update is needed.
     *
     * @return true or false depending on whether an update is needed
     */
    public boolean isUpdateNeeded() {
        return updateable.isUpdateNeeded();
    }

    /**
     * Set the value of deleteAndRecreate.
     *
     * @param deleteAndRecreate new value of deleteAndRecreate
     */
    protected void setDeleteAndRecreate(boolean deleteAndRecreate) {
        this.deleteAndRecreate = deleteAndRecreate;
    }

    /**
     * Get the value of deleteAndRecreate.
     *
     * @return the value of deleteAndRecreate
     */
    public boolean shouldDeleteAndRecreate() {
        return deleteAndRecreate;
    }

    /**
     * Constructs a new Standard Update Task.
     *
     * @throws MalformedURLException thrown if a configured URL is malformed
     * @throws DownloadFailedException thrown if a timestamp cannot be checked
     * on a configured URL
     * @throws UpdateException thrown if there is an exception generating the
     * update task
     */
    public StandardUpdate() throws MalformedURLException, DownloadFailedException, UpdateException {
        properties = new DataStoreMetaInfo();
        updateable = updatesNeeded();
    }

    /**
     * <p>Downloads the latest NVD CVE XML file from the web and imports it into
     * the current CVE Database.</p>
     *
     * @throws UpdateException is thrown if there is an error updating the
     * database
     */
    public void update() throws UpdateException {
        int maxUpdates = 0;
        try {
            for (NvdCveInfo cve : updateable) {
                if (cve.getNeedsUpdate()) {
                    maxUpdates += 1;
                }
            }
            if (maxUpdates <= 0) {
                return;
            }
            if (maxUpdates > 3) {
                Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
                        "NVD CVE requires several updates; this could take a couple of minutes.");
            }
            if (maxUpdates > 0) {
                openDataStores();
            }

            final int poolSize = (MAX_THREAD_POOL_SIZE > maxUpdates) ? MAX_THREAD_POOL_SIZE : maxUpdates;
            final ExecutorService downloadExecutor = Executors.newFixedThreadPool(poolSize);
            final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
            final Set<Future<CallableDownloadTask>> downloadFutures = new HashSet<Future<CallableDownloadTask>>(maxUpdates);
            final Set<Future<ProcessTask>> processFutures = new HashSet<Future<ProcessTask>>(maxUpdates);
            int ctr = 0;
            for (NvdCveInfo cve : updateable) {
                if (cve.getNeedsUpdate()) {
                    ctr += 1;
                    final File file1;
                    final File file2;
                    try {
                        file1 = File.createTempFile("cve" + cve.getId() + "_", ".xml");
                        file2 = File.createTempFile("cve_1_2_" + cve.getId() + "_", ".xml");
                    } catch (IOException ex) {
                        throw new UpdateException(ex);
                    }
                    final CallableDownloadTask call = new CallableDownloadTask(cve, file1, file2);
                    downloadFutures.add(downloadExecutor.submit(call));

                    boolean waitForFuture = ctr % 2 == 0;

                    final Iterator<Future<CallableDownloadTask>> itr = downloadFutures.iterator();
                    while (itr.hasNext()) {
                        final Future<CallableDownloadTask> future = itr.next();
                        if (waitForFuture) { //only allow two NVD/CVE files to be downloaded at a time
                            spinWaitForFuture(future);
                        }
                        if (future.isDone()) { //if we find something complete, add it to the process queue
                            try {
                                final CallableDownloadTask filePair = future.get();
                                itr.remove();
                                final ProcessTask task = new ProcessTask(cveDB, properties, filePair);
                                processFutures.add(processExecutor.submit(task));
                            } catch (InterruptedException ex) {
                                downloadExecutor.shutdownNow();
                                Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Thread was interupted", ex);
                                throw new UpdateException(ex);
                            } catch (ExecutionException ex) {
                                downloadExecutor.shutdownNow();
                                Logger.getLogger(StandardUpdate.class.getName()).log(Level.SEVERE, null, ex);
                                throw new UpdateException(ex);
                            }
                        }
                    }

                }
            }

            try {
                final Iterator<Future<CallableDownloadTask>> itr = downloadFutures.iterator();
                while (itr.hasNext()) {
                    final Future<CallableDownloadTask> future = itr.next();
                    final CallableDownloadTask filePair = future.get();
                    final ProcessTask task = new ProcessTask(cveDB, properties, filePair);
                    processFutures.add(processExecutor.submit(task));
                }
            } catch (InterruptedException ex) {
                downloadExecutor.shutdownNow();
                Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Thread was interupted during download", ex);
                throw new UpdateException(ex);
            } catch (ExecutionException ex) {
                downloadExecutor.shutdownNow();
                Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Execution Exception during download", ex);
                throw new UpdateException(ex);
            } finally {
                downloadExecutor.shutdown();
            }

            for (Future<ProcessTask> future : processFutures) {
                try {
                    final ProcessTask task = future.get();
                    if (task.getException() != null) {
                        throw task.getException();
                    }
                } catch (InterruptedException ex) {
                    processExecutor.shutdownNow();
                    Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Thread was interupted during processing", ex);
                    throw new UpdateException(ex);
                } catch (ExecutionException ex) {
                    processExecutor.shutdownNow();
                    Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Execution Exception during process", ex);
                    throw new UpdateException(ex);
                } finally {
                    processExecutor.shutdown();
                }
            }

            if (maxUpdates >= 1) { //ensure the modified file date gets written
                properties.save(updateable.get(MODIFIED));
                cveDB.cleanupDatabase();
            }
        } finally {
            closeDataStores();
        }
    }

    //<editor-fold defaultstate="collapsed" desc="OLD version of update() - not multithreaded">
    /*
     * TODO - remove this
     public void update() throws UpdateException {
     try {
     int maxUpdates = 0;
     for (NvdCveInfo cve : getUpdateable()) {
     if (cve.getNeedsUpdate()) {
     maxUpdates += 1;
     }
     }
     if (maxUpdates > 3) {
     Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
     "NVD CVE requires several updates; this could take a couple of minutes.");
     }
     if (maxUpdates > 0) {
     openDataStores();
     }

     int count = 0;
     for (NvdCveInfo cve : getUpdateable()) {
     if (cve.getNeedsUpdate()) {
     count += 1;
     Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
     "Updating NVD CVE ({0} of {1})", new Object[]{count, maxUpdates});
     URL url = new URL(cve.getUrl());
     File outputPath = null;
     File outputPath12 = null;
     try {
     Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
     "Downloading {0}", cve.getUrl());
     outputPath = File.createTempFile("cve" + cve.getId() + "_", ".xml");
     Downloader.fetchFile(url, outputPath);

     url = new URL(cve.getOldSchemaVersionUrl());
     outputPath12 = File.createTempFile("cve_1_2_" + cve.getId() + "_", ".xml");
     Downloader.fetchFile(url, outputPath12);

     Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
     "Processing {0}", cve.getUrl());

     importXML(outputPath, outputPath12);

     getCveDB().commit();
     getProperties().save(cve);

     Logger.getLogger(StandardUpdate.class.getName()).log(Level.INFO,
     "Completed update {0} of {1}", new Object[]{count, maxUpdates});
     } catch (FileNotFoundException ex) {
     throw new UpdateException(ex);
     } catch (ParserConfigurationException ex) {
     throw new UpdateException(ex);
     } catch (SAXException ex) {
     throw new UpdateException(ex);
     } catch (IOException ex) {
     throw new UpdateException(ex);
     } catch (SQLException ex) {
     throw new UpdateException(ex);
     } catch (DatabaseException ex) {
     throw new UpdateException(ex);
     } catch (ClassNotFoundException ex) {
     throw new UpdateException(ex);
     } finally {
     boolean deleted = false;
     try {
     if (outputPath != null && outputPath.exists()) {
     deleted = outputPath.delete();
     }
     } finally {
     if (outputPath != null && (outputPath.exists() || !deleted)) {
     outputPath.deleteOnExit();
     }
     }
     try {
     deleted = false;
     if (outputPath12 != null && outputPath12.exists()) {
     deleted = outputPath12.delete();
     }
     } finally {
     if (outputPath12 != null && (outputPath12.exists() || !deleted)) {
     outputPath12.deleteOnExit();
     }
     }
     }
     }
     }
     if (maxUpdates >= 1) { //ensure the modified file date gets written
     getProperties().save(getUpdateable().get(MODIFIED));
     getCveDB().cleanupDatabase();
     }
     } catch (MalformedURLException ex) {
     throw new UpdateException(ex);
     } finally {
     closeDataStores();
     }
     }
     */
    //</editor-fold>
    /**
     * Determines if the index needs to be updated. This is done by fetching the
     * NVD CVE meta data and checking the last update date. If the data needs to
     * be refreshed this method will return the NvdCveUrl for the files that
     * need to be updated.
     *
     * @return the collection of files that need to be updated
     * @throws MalformedURLException is thrown if the URL for the NVD CVE Meta
     * data is incorrect
     * @throws DownloadFailedException is thrown if there is an error.
     * downloading the NVD CVE download data file
     * @throws UpdateException Is thrown if there is an issue with the last
     * updated properties file
     */
    protected final Updateable updatesNeeded() throws MalformedURLException, DownloadFailedException, UpdateException {
        Updateable updates = null;
        try {
            updates = retrieveCurrentTimestampsFromWeb();
        } catch (InvalidDataException ex) {
            final String msg = "Unable to retrieve valid timestamp from nvd cve downloads page";
            Logger
                    .getLogger(StandardUpdate.class
                    .getName()).log(Level.FINE, msg, ex);
            throw new DownloadFailedException(msg, ex);
        } catch (InvalidSettingException ex) {
            Logger.getLogger(StandardUpdate.class
                    .getName()).log(Level.FINE, "Invalid setting found when retrieving timestamps", ex);
            throw new DownloadFailedException(
                    "Invalid settings", ex);
        }

        if (updates == null) {
            throw new DownloadFailedException("Unable to retrieve the timestamps of the currently published NVD CVE data");
        }
        if (!properties.isEmpty()) {
            try {
                float version;

                if (properties.getProperty("version") == null) {
                    deleteAndRecreate = true;
                } else {
                    try {
                        version = Float.parseFloat(properties.getProperty("version"));
                        final float currentVersion = Float.parseFloat(CveDB.DB_SCHEMA_VERSION);
                        if (currentVersion > version) {
                            deleteAndRecreate = true;
                        }
                    } catch (NumberFormatException ex) {
                        deleteAndRecreate = true;
                    }
                }

                if (deleteAndRecreate) {
                    return updates;
                }

                final long lastUpdated = Long.parseLong(properties.getProperty(DataStoreMetaInfo.LAST_UPDATED, "0"));
                final Date now = new Date();
                final int days = Settings.getInt(Settings.KEYS.CVE_MODIFIED_VALID_FOR_DAYS, 7);
                if (lastUpdated == updates.getTimeStamp(MODIFIED)) {
                    updates.clear(); //we don't need to update anything.
                } else if (withinRange(lastUpdated, now.getTime(), days)) {
                    for (NvdCveInfo entry : updates) {
                        if (MODIFIED.equals(entry.getId())) {
                            entry.setNeedsUpdate(true);
                        } else {
                            entry.setNeedsUpdate(false);
                        }
                    }
                } else { //we figure out which of the several XML files need to be downloaded.
                    for (NvdCveInfo entry : updates) {
                        if (MODIFIED.equals(entry.getId())) {
                            entry.setNeedsUpdate(true);
                        } else {
                            long currentTimestamp = 0;
                            try {
                                currentTimestamp = Long.parseLong(properties.getProperty(DataStoreMetaInfo.LAST_UPDATED_BASE + entry.getId(), "0"));
                            } catch (NumberFormatException ex) {
                                final String msg = String.format("Error parsing '%s' '%s' from nvdcve.lastupdated",
                                        DataStoreMetaInfo.LAST_UPDATED_BASE, entry.getId());
                                Logger
                                        .getLogger(StandardUpdate.class
                                        .getName()).log(Level.FINE, msg, ex);
                            }
                            if (currentTimestamp == entry.getTimestamp()) {
                                entry.setNeedsUpdate(false);
                            }
                        }
                    }
                }
            } catch (NumberFormatException ex) {
                final String msg = "An invalid schema version or timestamp exists in the data.properties file.";
                Logger
                        .getLogger(StandardUpdate.class
                        .getName()).log(Level.WARNING, msg);
                Logger.getLogger(StandardUpdate.class
                        .getName()).log(Level.FINE, null, ex);
            }
        }
        return updates;
    }

    /**
     * Retrieves the timestamps from the NVD CVE meta data file.
     *
     * @return the timestamp from the currently published nvdcve downloads page
     * @throws MalformedURLException thrown if the URL for the NVD CCE Meta data
     * is incorrect.
     * @throws DownloadFailedException thrown if there is an error downloading
     * the nvd cve meta data file
     * @throws InvalidDataException thrown if there is an exception parsing the
     * timestamps
     * @throws InvalidSettingException thrown if the settings are invalid
     */
    private Updateable retrieveCurrentTimestampsFromWeb()
            throws MalformedURLException, DownloadFailedException, InvalidDataException, InvalidSettingException {

        final Updateable updates = new Updateable();
        updates.add(MODIFIED, Settings.getString(Settings.KEYS.CVE_MODIFIED_20_URL),
                Settings.getString(Settings.KEYS.CVE_MODIFIED_12_URL),
                false);

        final int start = Settings.getInt(Settings.KEYS.CVE_START_YEAR);
        final int end = Calendar.getInstance().get(Calendar.YEAR);
        final String baseUrl20 = Settings.getString(Settings.KEYS.CVE_SCHEMA_2_0);
        final String baseUrl12 = Settings.getString(Settings.KEYS.CVE_SCHEMA_1_2);
        for (int i = start; i <= end; i++) {
            updates.add(Integer.toString(i), String.format(baseUrl20, i),
                    String.format(baseUrl12, i),
                    true);
        }

        return updates;
    }

    /**
     * Deletes the existing data directories.
     *
     * @throws IOException thrown if the directory cannot be deleted
     */
    protected void deleteExistingData() throws IOException {
        File data = Settings.getDataFile(Settings.KEYS.CVE_DATA_DIRECTORY);
        if (data.exists()) {
            FileUtils.delete(data);
        }
        data = DataStoreMetaInfo.getPropertiesFile();
        if (data.exists()) {
            FileUtils.delete(data);
        }
    }

    /**
     * Closes the CVE and CPE data stores.
     */
    protected void closeDataStores() {
        if (cveDB != null) {
            try {
                cveDB.close();
            } catch (Exception ignore) {
                Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINEST, "Error closing the cveDB", ignore);
            }
        }
    }

    /**
     * Opens the CVE and CPE data stores.
     *
     * @throws UpdateException thrown if a data store cannot be opened
     */
    protected void openDataStores() throws UpdateException {
        //open the cve and cpe data stores
        try {
            cveDB = new CveDB();
            cveDB.open();
        } catch (IOException ex) {
            closeDataStores();
            Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "IO Error opening databases", ex);
            throw new UpdateException("Error updating the CPE/CVE data, please see the log file for more details.");
        } catch (SQLException ex) {
            closeDataStores();
            Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "SQL Exception opening databases", ex);
            throw new UpdateException("Error updating the CPE/CVE data, please see the log file for more details.");
        } catch (DatabaseException ex) {
            closeDataStores();
            Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Database Exception opening databases", ex);
            throw new UpdateException("Error updating the CPE/CVE data, please see the log file for more details.");
        } catch (ClassNotFoundException ex) {
            closeDataStores();
            Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, "Class not found exception opening databases", ex);
            throw new UpdateException("Error updating the CPE/CVE data, please see the log file for more details.");
        }
    }

    /**
     * Determines if the epoch date is within the range specified of the
     * compareTo epoch time. This takes the (compareTo-date)/1000/60/60/24 to
     * get the number of days. If the calculated days is less then the range the
     * date is considered valid.
     *
     * @param date the date to be checked.
     * @param compareTo the date to compare to.
     * @param range the range in days to be considered valid.
     * @return whether or not the date is within the range.
     */
    protected boolean withinRange(long date, long compareTo, int range) {
        final double differenceInDays = (compareTo - date) / 1000.0 / 60.0 / 60.0 / 24.0;
        return differenceInDays < range;
    }

    private void spinWaitForFuture(final Future<CallableDownloadTask> future) {
        //then wait for downloads to finish
        while (!future.isDone()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Logger.getLogger(StandardUpdate.class.getName()).log(Level.FINE, null, ex);
            }
        }
    }
}