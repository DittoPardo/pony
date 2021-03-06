package net.dorokhov.pony.core.library;

import net.dorokhov.pony.core.audio.data.SongDataWritable;
import net.dorokhov.pony.core.dao.*;
import net.dorokhov.pony.core.domain.ScanResult;
import net.dorokhov.pony.core.domain.ScanType;
import net.dorokhov.pony.core.domain.Song;
import net.dorokhov.pony.core.domain.StoredFile;
import net.dorokhov.pony.core.library.exception.*;
import net.dorokhov.pony.core.library.file.LibraryFile;
import net.dorokhov.pony.core.library.file.LibraryFolder;
import net.dorokhov.pony.core.library.file.LibraryImage;
import net.dorokhov.pony.core.library.file.LibrarySong;
import net.dorokhov.pony.core.logging.LogService;
import net.dorokhov.pony.core.storage.StoredFileService;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ScanServiceImpl implements ScanService {

	private final static int NUMBER_OF_SCAN_THREADS = 10;
	private final static int NUMBER_OF_SCAN_STEPS = 6;

	private final static int STEP_SCAN_PREPARING = 1;
	private final static int STEP_SCAN_SEARCHING_MEDIA_FILES = 2;
	private final static int STEP_SCAN_CLEANING_SONGS = 3;
	private final static int STEP_SCAN_CLEANING_ARTWORKS = 4;
	private final static int STEP_SCAN_IMPORTING_SONGS = 5;
	private final static int STEP_SCAN_NORMALIZING = 6;

	private final static String STEP_CODE_SCAN_PREPARING = "preparing";
	private final static String STEP_CODE_SCAN_SEARCHING_MEDIA_FILES = "searchingMediaFiles";
	private final static String STEP_CODE_SCAN_CLEANING_SONGS = "cleaningSongs";
	private final static String STEP_CODE_SCAN_CLEANING_ARTWORKS = "cleaningArtworks";
	private final static String STEP_CODE_SCAN_IMPORTING_SONGS = "importingSongs";
	private final static String STEP_CODE_SCAN_NORMALIZING = "normalizing";

	private final static int NUMBER_OF_EDIT_THREADS = 10;
	private final static int NUMBER_OF_EDIT_STEPS = 3;

	private final static int STEP_EDIT_PREPARING = 1;
	private final static int STEP_EDIT_WRITING_SONGS = 2;
	private final static int STEP_EDIT_NORMALIZING = 3;

	private final static String STEP_CODE_EDIT_PREPARING = "preparing";
	private final static String STEP_CODE_EDIT_WRITING_SONGS = "writingSongs";
	private final static String STEP_CODE_EDIT_NORMALIZING = "normalizing";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Object delegatesLock = new Object();
	private final Object statusCheckLock = new Object();
	private final Object progressLock = new Object();

	private final List<Delegate> delegates = new ArrayList<>();

	private final AtomicReference<StatusImpl> statusReference = new AtomicReference<>();

	private final AtomicReference<ExecutorService> executorReference = new AtomicReference<>();

	private final AtomicInteger processedTaskCount = new AtomicInteger();

	private final List<String> failedPaths = Collections.synchronizedList(new ArrayList<String>());

	private TransactionTemplate transactionTemplate;

	private LogService logService;

	private ScanResultDao scanResultDao;

	private FileScanService fileScanService;

	private LibraryService libraryService;

	private SongDao songDao;
	private GenreDao genreDao;
	private ArtistDao artistDao;
	private AlbumDao albumDao;

	private StoredFileService storedFileService;

	@Autowired
	public void setTransactionManager(PlatformTransactionManager aTransactionManager) {
		transactionTemplate = new TransactionTemplate(aTransactionManager, new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
	}

	@Autowired
	public void setLogService(LogService aLogService) {
		logService = aLogService;
	}

	@Autowired
	public void setScanResultDao(ScanResultDao aScanResultDao) {
		scanResultDao = aScanResultDao;
	}

	@Autowired
	public void setFileScanService(FileScanService aFileScanService) {
		fileScanService = aFileScanService;
	}

	@Autowired
	public void setLibraryService(LibraryService aLibraryService) {
		libraryService = aLibraryService;
	}

	@Autowired
	public void setSongDao(SongDao aSongDao) {
		songDao = aSongDao;
	}

	@Autowired
	public void setGenreDao(GenreDao aGenreDao) {
		genreDao = aGenreDao;
	}

	@Autowired
	public void setArtistDao(ArtistDao aArtistDao) {
		artistDao = aArtistDao;
	}

	@Autowired
	public void setAlbumDao(AlbumDao aAlbumDao) {
		albumDao = aAlbumDao;
	}

	@Autowired
	public void setStoredFileService(StoredFileService aStoredFileService) {
		storedFileService = aStoredFileService;
	}

	@PreDestroy
	public void onPreDestroy() {

		ExecutorService executor = executorReference.get();

		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Override
	public void addDelegate(Delegate aDelegate) {
		synchronized (delegatesLock) {
			if (!delegates.contains(aDelegate)) {
				delegates.add(aDelegate);
			}
		}
	}

	@Override
	public void removeDelegate(Delegate aDelegate) {
		synchronized (delegatesLock) {
			delegates.remove(aDelegate);
		}
	}

	@Override
	public Status getStatus() {

		Status status = statusReference.get();

		return status != null ? new StatusImpl(status) : null;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ScanResult> getAll(Pageable aPageable) {
		return scanResultDao.findAll(aPageable);
	}

	@Override
	@Transactional(readOnly = true)
	public ScanResult getById(Long aId) {
		return scanResultDao.findOne(aId);
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ScanResult scan(final List<File> aTargetFolders) throws ConcurrentScanException, FileNotFoundException, NotFolderException {

		for (File folder : aTargetFolders) {
			if (!folder.exists()) {
				throw new FileNotFoundException(folder);
			}
			if (!folder.isDirectory()) {
				throw new NotFolderException(folder);
			}
		}

		synchronized (statusCheckLock) {

			if (statusReference.get() != null) {
				throw new ConcurrentScanException();
			}

			statusReference.set(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_PREPARING, STEP_CODE_SCAN_PREPARING, -1));
		}

		logService.info(log, "libraryScanService.scanStarted", "Scanning library " + aTargetFolders + "...",
				Arrays.asList(aTargetFolders.toString()));

		synchronized (delegatesLock) {
			for (Delegate next : new ArrayList<>(delegates)) {
				try {
					next.onScanStart(ScanType.FULL, new ArrayList<>(aTargetFolders));
				} catch (Exception e) {
					log.error("Exception thrown when delegating onScanStart to " + next, e);
				}
			}
		}

		updateStatus(statusReference.get());

		try {

			executorReference.set(Executors.newFixedThreadPool(NUMBER_OF_SCAN_THREADS, new BasicThreadFactory.Builder().namingPattern("pony-scan-import-%d").build()));

			ScanResult scanResult = transactionTemplate.execute(new TransactionCallback<ScanResult>() {
				@Override
				public ScanResult doInTransaction(TransactionStatus status) {
					return doScan(aTargetFolders);
				}
			});

			logService.info(log, "libraryScanService.scanFinished", "Scan of " + scanResult.getTargetPaths() + " has been finished with result " + scanResult.toString() + ".",
					Arrays.asList(StringUtils.join(scanResult.getTargetPaths(), ", "), scanResult.toString()));

			synchronized (delegatesLock) {
				for (Delegate next : new ArrayList<>(delegates)) {
					try {
						next.onScanFinish(scanResult);
					} catch (Exception e) {
						log.error("Exception thrown when delegating onScanFinish to " + next, e);
					}
				}
			}

			return scanResult;

		} catch (final Exception scanException) {

			logService.error(log, "libraryScanService.scanFailed", "Scan failed.", scanException);

			synchronized (delegatesLock) {
				for (Delegate next : new ArrayList<>(delegates)) {
					try {
						next.onScanFail(scanException);
					} catch (Exception e) {
						log.error("Exception thrown when delegating onScanFail to " + next, e);
					}
				}
			}

			throw new RuntimeException(scanException);

		} finally {
			executorReference.set(null);
			processedTaskCount.set(0);
			failedPaths.clear();
			statusReference.set(null);
		}
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ScanResult edit(List<ScanEditCommand> aCommands) throws SongNotFoundException, ConcurrentScanException, FileNotFoundException, NotSongException {

		final List<File> targetFiles = new ArrayList<>();
		final List<String> targetPaths = new ArrayList<>();
		final List<EditCommand> editCommands = new ArrayList<>();

		for (ScanEditCommand command : aCommands) {

			Song song = songDao.findOne(command.getSongId());

			if (song != null) {

				File songFile = new File(song.getPath());

				if (songFile.exists()) {

					targetFiles.add(songFile);
					targetPaths.add(song.getPath());

					LibraryFile libraryFile = fileScanService.scanFile(songFile);

					if (libraryFile instanceof LibrarySong) {
						editCommands.add(new EditCommand((LibrarySong) libraryFile, command.getSongData()));
					} else {
						throw new NotSongException(songFile);
					}
				} else {
					throw new FileNotFoundException(songFile);
				}
			} else {
				throw new SongNotFoundException(command.getSongId());
			}
		}

		synchronized (statusCheckLock) {

			if (statusReference.get() != null) {
				throw new ConcurrentScanException();
			}

			statusReference.set(StatusImpl.buildEditStatus(targetFiles, STEP_EDIT_PREPARING, STEP_CODE_EDIT_PREPARING, -1));
		}

		logService.info(log, "libraryScanService.editStarted", "Editing files " + targetFiles + "...", targetPaths);

		synchronized (delegatesLock) {
			for (Delegate next : new ArrayList<>(delegates)) {
				try {
					next.onScanStart(ScanType.EDIT, new ArrayList<>(targetFiles));
				} catch (Exception e) {
					log.error("Exception thrown when delegating onScanStart to " + next, e);
				}
			}
		}

		updateStatus(statusReference.get());

		try {

			executorReference.set(Executors.newFixedThreadPool(NUMBER_OF_EDIT_THREADS, new BasicThreadFactory.Builder().namingPattern("pony-edit-import-%d").build()));

			ScanResult scanResult = transactionTemplate.execute(new TransactionCallback<ScanResult>() {
				@Override
				public ScanResult doInTransaction(TransactionStatus status) {
					return doEdit(editCommands);
				}
			});

			logService.info(log, "libraryScanService.editFinished", "Edit of files " + scanResult.getTargetPaths() + " has been finished with result " + scanResult.toString() + ".",
					Arrays.asList(StringUtils.join(scanResult.getTargetPaths(), ", "), scanResult.toString()));

			synchronized (delegatesLock) {
				for (Delegate next : new ArrayList<>(delegates)) {
					try {
						next.onScanFinish(scanResult);
					} catch (Exception e) {
						log.error("Exception thrown when delegating onScanFinish to " + next, e);
					}
				}
			}

			return scanResult;

		} catch (final Exception editException) {

			logService.error(log, "libraryScanService.editFailed", "Edit failed.", editException);

			synchronized (delegatesLock) {
				for (Delegate next : new ArrayList<>(delegates)) {
					try {
						next.onScanFail(editException);
					} catch (Exception e) {
						log.error("Exception thrown when delegating onScanFail to " + next, e);
					}
				}
			}

			throw new RuntimeException(editException);

		} finally {
			executorReference.set(null);
			processedTaskCount.set(0);
			failedPaths.clear();
			statusReference.set(null);
		}
	}

	private ScanResult doScan(final List<File> aTargetFolders) {

		List<String> targetPaths = new ArrayList<>();
		for (File folder : aTargetFolders) {
			targetPaths.add(folder.getAbsolutePath());
		}

		return calculateScanResult(ScanType.FULL, targetPaths, new ScanProcessor() {
			@Override
			public int process() {
				return performScanSteps(aTargetFolders);
			}
		});
	}

	private ScanResult doEdit(final List<EditCommand> aCommands) {

		List<String> targetPaths = new ArrayList<>();
		for (EditCommand command : aCommands) {
			targetPaths.add(command.getSongFile().getFile().getAbsolutePath());
		}

		return calculateScanResult(ScanType.EDIT, targetPaths, new ScanProcessor() {
			@Override
			public int process() {
				return performEditSteps(aCommands);
			}
		});
	}

	private int performScanSteps(final List<File> aTargetFolders) {

		logService.info(log, "libraryScanService.searchingMediaFiles", "Searching media files...");
		updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_SEARCHING_MEDIA_FILES, STEP_CODE_SCAN_SEARCHING_MEDIA_FILES, -1.0));
		List<LibrarySong> songFiles = new ArrayList<>();
		List<LibraryImage> imageFiles = new ArrayList<>();
		for (File targetFolder : aTargetFolders) {

			LibraryFolder libraryFolder = fileScanService.scanFolder(targetFolder);

			songFiles.addAll(libraryFolder.getChildSongs(true));
			imageFiles.addAll(libraryFolder.getChildImages(true));
		}

		logService.info(log, "libraryScanService.cleaningSongs", "Cleaning songs...");
		updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_CLEANING_SONGS, STEP_CODE_SCAN_CLEANING_SONGS, 0.0));
		libraryService.cleanSongs(songFiles, new LibraryService.ProgressDelegate() {
			@Override
			public void onProgress(double aProgress) {
				updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_CLEANING_SONGS, STEP_CODE_SCAN_CLEANING_SONGS, aProgress));
			}
		});

		logService.info(log, "libraryScanService.cleaningArtworks", "Cleaning artworks...");
		updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_CLEANING_ARTWORKS, STEP_CODE_SCAN_CLEANING_ARTWORKS, 0.0));
		libraryService.cleanArtworks(imageFiles, new LibraryService.ProgressDelegate() {
			@Override
			public void onProgress(double aProgress) {
				updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_CLEANING_ARTWORKS, STEP_CODE_SCAN_CLEANING_ARTWORKS, aProgress));
			}
		});

		logService.info(log, "libraryScanService.importingSongs", "Importing songs...");
		updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_IMPORTING_SONGS, STEP_CODE_SCAN_IMPORTING_SONGS, 0.0));

		Collections.sort(songFiles, new Comparator<LibrarySong>() {
			@Override
			public int compare(LibrarySong song1, LibrarySong song2) {
				return song1.getFile().getAbsolutePath().compareTo(song2.getFile().getAbsolutePath());
			}
		});

		ExecutorService executor = executorReference.get();

		List<Future<Void>> futureList = new ArrayList<>();
		for (LibrarySong file : songFiles) {
			futureList.add(executor.submit(new ImportSongTask(aTargetFolders, file, songFiles.size())));
		}
		for (Future<Void> future : futureList) {
			try {
				future.get();
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		}
		executor.shutdown();

		logService.info(log, "libraryScanService.normalizing", "Normalizing...");
		updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_NORMALIZING, STEP_CODE_SCAN_NORMALIZING, 0.0));
		libraryService.normalize(new LibraryService.ProgressDelegate() {
			@Override
			public void onProgress(double aProgress) {
				updateStatus(StatusImpl.buildScanStatus(aTargetFolders, STEP_SCAN_NORMALIZING, STEP_CODE_SCAN_NORMALIZING, aProgress));
			}
		});

		return songFiles.size();
	}

	private int performEditSteps(List<EditCommand> aCommands) {

		final List<File> targetFiles = new ArrayList<>();
		for (EditCommand command : aCommands) {
			targetFiles.add(command.getSongFile().getFile());
		}

		logService.info(log, "libraryScanService.writingSongs", "Importing songs...");
		updateStatus(StatusImpl.buildScanStatus(targetFiles, STEP_EDIT_WRITING_SONGS, STEP_CODE_EDIT_WRITING_SONGS, 0.0));

		ExecutorService executor = executorReference.get();

		List<Future<Void>> futureList = new ArrayList<>();
		for (EditCommand command : aCommands) {
			futureList.add(executor.submit(new WriteSongTask(targetFiles, command, aCommands.size())));
		}
		for (Future<Void> future : futureList) {
			try {
				future.get();
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		}
		executor.shutdown();

		logService.info(log, "libraryScanService.normalizing", "Normalizing...");
		updateStatus(StatusImpl.buildScanStatus(targetFiles, STEP_EDIT_NORMALIZING, STEP_CODE_EDIT_NORMALIZING, 0.0));
		libraryService.normalize(new LibraryService.ProgressDelegate() {
			@Override
			public void onProgress(double aProgress) {
				updateStatus(StatusImpl.buildScanStatus(targetFiles, STEP_EDIT_NORMALIZING, STEP_CODE_EDIT_NORMALIZING, aProgress));
			}
		});

		return aCommands.size();
	}

	private void updateStatus(StatusImpl aStatus) {

		statusReference.set(aStatus);

		synchronized (delegatesLock) {
			for (Delegate next : new ArrayList<>(delegates)) {
				try {
					next.onScanProgress(aStatus);
				} catch (Exception e) {
					log.error("Exception thrown when delegating onScanProgress to " + next, e);
				}
			}
		}
	}

	private ScanResult calculateScanResult(ScanType aType, List<String> aPaths, ScanProcessor aProcessor) {

		Page<ScanResult> scanResults = scanResultDao.findAll(new PageRequest(0, 1, Sort.Direction.DESC, "date"));

		ScanResult lastScan = scanResults.hasContent() ? scanResults.getContent().get(0) : null;

		Date lastScanDate = (lastScan != null ? lastScan.getDate() : new Date(0L));

		long songCountBeforeScan = songDao.count();
		long genreCountBeforeScan = genreDao.count();
		long artistCountBeforeScan = artistDao.count();
		long albumCountBeforeScan = albumDao.count();
		long artworkCountBeforeScan = storedFileService.getCountByTag(StoredFile.TAG_ARTWORK_EMBEDDED) + storedFileService.getCountByTag(StoredFile.TAG_ARTWORK_FILE);

		long startTime = System.nanoTime();

		int processedItemsCount = aProcessor.process();

		long endTime = System.nanoTime();

		long songCountAfterScan = songDao.count();
		long songCountCreated = songDao.countByCreationDateGreaterThan(lastScanDate);
		long songCountUpdated = songDao.countByCreationDateLessThanAndUpdateDateGreaterThan(lastScanDate, lastScanDate);
		long songCountDeleted = Math.max(0, songCountBeforeScan - (songCountAfterScan - songCountCreated));

		long genreCountAfterScan = genreDao.count();
		long genreCountCreated = genreDao.countByCreationDateGreaterThan(lastScanDate);
		long genreCountUpdated = genreDao.countByCreationDateLessThanAndUpdateDateGreaterThan(lastScanDate, lastScanDate);
		long genreCountDeleted = Math.max(0, genreCountBeforeScan - (genreCountAfterScan - genreCountCreated));

		long artistCountAfterScan = artistDao.count();
		long artistCountCreated = artistDao.countByCreationDateGreaterThan(lastScanDate);
		long artistCountUpdated = artistDao.countByCreationDateLessThanAndUpdateDateGreaterThan(lastScanDate, lastScanDate);
		long artistCountDeleted = Math.max(0, artistCountBeforeScan - (artistCountAfterScan - artistCountCreated));

		long albumCountAfterScan = albumDao.count();
		long albumCountCreated = albumDao.countByCreationDateGreaterThan(lastScanDate);
		long albumCountUpdated = albumDao.countByCreationDateLessThanAndUpdateDateGreaterThan(lastScanDate, lastScanDate);
		long albumCountDeleted = Math.max(0, albumCountBeforeScan - (albumCountAfterScan - albumCountCreated));

		long artworkCountAfterScan = storedFileService.getCountByTag(StoredFile.TAG_ARTWORK_EMBEDDED) + storedFileService.getCountByTag(StoredFile.TAG_ARTWORK_FILE);
		long artworkCountCreated = storedFileService.getCountByTagAndMinimalDate(StoredFile.TAG_ARTWORK_EMBEDDED, lastScanDate) +
				storedFileService.getCountByTagAndMinimalDate(StoredFile.TAG_ARTWORK_FILE, lastScanDate);
		long artworkCountDeleted = Math.max(0, artworkCountBeforeScan - (artworkCountAfterScan - artworkCountCreated));

		ScanResult scanResult = new ScanResult();

		scanResult.setTargetPaths(aPaths);
		scanResult.setFailedPaths(new ArrayList<>(failedPaths));

		scanResult.setScanType(aType);
		scanResult.setDuration((endTime - startTime) / 1000000);

		scanResult.setSongSize(ObjectUtils.defaultIfNull(songDao.sumSize(), 0L));
		scanResult.setArtworkSize(storedFileService.getSizeByTag(StoredFile.TAG_ARTWORK_EMBEDDED) + storedFileService.getSizeByTag(StoredFile.TAG_ARTWORK_FILE));

		scanResult.setGenreCount(genreCountAfterScan);
		scanResult.setArtistCount(artistCountAfterScan);
		scanResult.setAlbumCount(albumCountAfterScan);
		scanResult.setSongCount(songCountAfterScan);
		scanResult.setArtworkCount(artworkCountAfterScan);

		scanResult.setProcessedSongCount(Integer.valueOf(processedItemsCount).longValue());

		scanResult.setCreatedArtistCount(artistCountCreated);
		scanResult.setUpdatedArtistCount(artistCountUpdated);
		scanResult.setDeletedArtistCount(artistCountDeleted);

		scanResult.setCreatedAlbumCount(albumCountCreated);
		scanResult.setUpdatedAlbumCount(albumCountUpdated);
		scanResult.setDeletedAlbumCount(albumCountDeleted);

		scanResult.setCreatedGenreCount(genreCountCreated);
		scanResult.setUpdatedGenreCount(genreCountUpdated);
		scanResult.setDeletedGenreCount(genreCountDeleted);

		scanResult.setCreatedSongCount(songCountCreated);
		scanResult.setUpdatedSongCount(songCountUpdated);
		scanResult.setDeletedSongCount(songCountDeleted);

		scanResult.setCreatedArtworkCount(artworkCountCreated);
		scanResult.setDeletedArtworkCount(artworkCountDeleted);

		return scanResultDao.save(scanResult);
	}

	private interface ScanProcessor {
		public int process();
	}

	private static class StatusImpl implements Status {

		private final ScanType type;
		private final List<File> files;
		private final int step;
		private final String stepCode;
		private final int totalSteps;
		private final double progress;

		public StatusImpl(ScanType aType, List<File> aFiles, int aStep, String aStepCode, int aTotalSteps, double aProgress) {
			type = aType;
			files = aFiles != null ? new ArrayList<>(aFiles) : null;
			step = aStep;
			stepCode = aStepCode;
			totalSteps = aTotalSteps;
			progress = aProgress;
		}

		public StatusImpl(Status aStatus) {
			this(aStatus.getScanType(), aStatus.getFiles(), aStatus.getStep(), aStatus.getStepCode(), aStatus.getTotalSteps(), aStatus.getProgress());
		}

		@Override
		public ScanType getScanType() {
			return type;
		}

		@Override
		public List<File> getFiles() {
			return files != null ? new ArrayList<>(files) : null;
		}

		@Override
		public String getStepCode() {
			return stepCode;
		}

		@Override
		public double getProgress() {
			return progress;
		}

		@Override
		public int getStep() {
			return step;
		}

		@Override
		public int getTotalSteps() {
			return totalSteps;
		}

		public static StatusImpl buildScanStatus(List<File> aFiles, int aStep, String aStepCode, double aProgress) {
			return new StatusImpl(ScanType.FULL, aFiles, aStep, aStepCode, NUMBER_OF_SCAN_STEPS, aProgress);
		}

		public static StatusImpl buildEditStatus(List<File> aFiles, int aStep, String aStepCode, double aProgress) {
			return new StatusImpl(ScanType.EDIT, aFiles, aStep, aStepCode, NUMBER_OF_EDIT_STEPS, aProgress);
		}
	}

	private class EditCommand {

		private LibrarySong songFile;

		private SongDataWritable songData;

		private EditCommand(LibrarySong aSongFile, SongDataWritable aSongData) {
			songFile = aSongFile;
			songData = aSongData;
		}

		public LibrarySong getSongFile() {
			return songFile;
		}

		public SongDataWritable getSongData() {
			return songData;
		}
	}

	private class ImportSongTask implements Callable<Void> {

		private final List<File> targetFolders;

		private final LibrarySong songFile;

		private final int taskCount;

		private ImportSongTask(List<File> aTargetFolders, LibrarySong aSongFile, int aTaskCount) {
			targetFolders = aTargetFolders;
			songFile = aSongFile;
			taskCount = aTaskCount;
		}

		@Override
		public Void call() throws Exception {

			try {
				libraryService.importSong(songFile);
			} catch (Exception e) {

				logService.warn(log, "libraryScanService.songImportFailed", "Could not import song from file [" + songFile.getFile().getAbsolutePath() + "].",
						e, Arrays.asList(songFile.getFile().getAbsolutePath()));

				failedPaths.add(songFile.getFile().getAbsolutePath());
			}

			synchronized (progressLock) {

				double progress = processedTaskCount.incrementAndGet() / (double) taskCount;

				updateStatus(StatusImpl.buildScanStatus(targetFolders, STEP_SCAN_IMPORTING_SONGS, STEP_CODE_SCAN_IMPORTING_SONGS, progress));
			}

			return null;
		}
	}

	private class WriteSongTask implements Callable<Void> {

		private final List<File> targetFiles;

		private final EditCommand command;

		private final int taskCount;

		private WriteSongTask(List<File> aTargetFiles, EditCommand aCommand, int aTaskCount) {
			targetFiles = aTargetFiles;
			command = aCommand;
			taskCount = aTaskCount;
		}

		@Override
		public Void call() throws Exception {

			try {
				libraryService.writeAndImportSong(command.getSongFile(), command.getSongData());
			} catch (Exception e) {

				logService.warn(log, "libraryScanService.songWriteFailed", "Could not write song file [" + command.getSongFile().getFile().getAbsolutePath() + "].",
						e, Arrays.asList(command.getSongFile().getFile().getAbsolutePath()));

				failedPaths.add(command.getSongFile().getFile().getAbsolutePath());
			}

			synchronized (progressLock) {

				double progress = processedTaskCount.incrementAndGet() / (double) taskCount;

				updateStatus(StatusImpl.buildScanStatus(targetFiles, STEP_EDIT_WRITING_SONGS, STEP_CODE_EDIT_WRITING_SONGS, progress));
			}

			return null;
		}

	}

}
