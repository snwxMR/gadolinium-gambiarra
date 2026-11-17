// SPDX-License-Identifier: MIT
package nthrc.rc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import gambiarra.common.ValidationLog;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.IThreadedFileIO;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraft.world.storage.WorldSavedData;
import nthrc.WorldgenConfig;
import nthrc.WorldgenStatus;

/**
 * saveStructureDataOffThread (see MapStorageSaveMixin). The game's MapStorage.saveData does, on the server thread: root = {"data": data.writeToNBT(new)}, opens the file and writes the compressed root
 * (CompressedStreamTools.writeCompressed).
 *
 * Here, for Recurrent Complex's WorldStructureGenerationData (reccomplex-structuredata.dat, which only grows):
 *  - on the server thread: the same root (writeToNBT, which needs to see the data as it is now) and a deep copy of it. Recurrent Complex puts its own
 *    instanceData compounds in the root without copying them; NBTTagCompound.copy copies every tag below it (lists, arrays and strings too; checked
 *    on the Cleanroom 0.7.14 classes), so the disk thread only sees the copy, never what Recurrent Complex goes on changing;
 *  - on the game's disk thread (ThreadedFileIOBase, the same as for chunks): the compression of the copy, to a temporary file alongside, swapped for the final one at the end (the game writes straight to
 *    the final one: if it closes midway, the file ends up cut; here the previous whole one stays).
 * When closing the world, the game waits for the disk queue (WorldServer.flush -> AnvilSaveHandler.flush -> waitForFinish), so the file is complete before the world closes. The queue is a single thread, in order:
 * the last save ends up on top. The same content; the bytes can differ in key order (see Write.check).
 */
public final class StructureDataWriter {

    /** The class of Recurrent Complex's structure data (by name: MapStorage is the game's and does not know RC). */
    private static final String RC_DATA = "ivorius.reccomplex.world.gen.feature.WorldStructureGenerationData";

    /** Test (development build only): also writes the bytes the game would write and checks the file at the end. */
    private static final boolean VALIDATE = validateRequested();

    private static final AtomicLong queued = new AtomicLong(), written = new AtomicLong(), failed = new AtomicLong();
    private static final AtomicLong bytes = new AtomicLong(), maxWriteMs = new AtomicLong(), totalWriteMs = new AtomicLong();
    private static final AtomicLong validated = new AtomicLong(), mismatches = new AtomicLong();
    /** Validation: files with the same bytes as the game's, and with the same content in another key order. */
    private static final AtomicLong sameBytes = new AtomicLong(), otherOrder = new AtomicLong();

    private StructureDataWriter() {
    }

    private static boolean validateRequested() {
        return WorldgenConfig.validate;
    }

    public static boolean handles(WorldSavedData data) {
        return RC_DATA.equals(data.getClass().getName());
    }

    /**
     * Does on the server thread what must be done there and sends the rest to the disk queue. false = did not find the file (the game, in that case, writes nothing; lets the game's path carry on).
     */
    public static boolean queue(ISaveHandler saveHandler, WorldSavedData data) {
        try {
            File file = saveHandler.getMapFileFromName(data.mapName);
            if (file == null) {
                return false;
            }
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", data.writeToNBT(new NBTTagCompound()));
            byte[] expected = null;
            if (VALIDATE) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(root, out);
                expected = out.toByteArray();
            }
            ThreadedFileIOBase.getThreadedIOInstance().queueIO(new Write(file, root.copy(), expected));
            queued.incrementAndGet();
        } catch (Exception e) {
            // The game does the same with the save exception.
            e.printStackTrace();
        }
        return true;
    }

    /** A write in the disk queue: runs once and leaves the queue. */
    private static final class Write implements IThreadedFileIO {

        private final File file;
        private final NBTTagCompound root;
        private final byte[] expected;

        Write(File file, NBTTagCompound root, byte[] expected) {
            this.file = file;
            this.root = root;
            this.expected = expected;
        }

        @Override
        public boolean writeNextIO() {
            long t0 = System.nanoTime();
            File tmp = new File(file.getPath() + ".gambiarra-tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    CompressedStreamTools.writeCompressed(root, out);
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                written.incrementAndGet();
                bytes.set(file.length());
                if (expected != null) {
                    check();
                }
            } catch (IOException | RuntimeException e) {
                failed.incrementAndGet();
                WorldgenStatus.LOG.error("[gambiarra] recurrentcomplex.saveStructureDataOffThread: could not write {}", file, e);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            totalWriteMs.addAndGet(ms);
            maxWriteMs.accumulateAndGet(ms, Math::max);
            return false;
        }

        /**
         * The content, entry by entry, not the bytes: Recurrent Complex puts each structure's stored instanceData compound in the output as it
         * is, and a HashMap that has grown and shrunk iterates in another order than its copy. Same keys and values in another order give
         * other bytes and the same data.
         */
        private void check() throws IOException {
            validated.incrementAndGet();
            byte[] onDisk = Files.readAllBytes(file.toPath());
            String difference = null;
            if (Arrays.equals(expected, onDisk)) {
                sameBytes.incrementAndGet();
            } else {
                NBTTagCompound game = CompressedStreamTools.readCompressed(new ByteArrayInputStream(expected));
                NBTTagCompound written = CompressedStreamTools.readCompressed(new ByteArrayInputStream(onDisk));
                difference = firstDifference("", game, written);
                if (difference == null) {
                    otherOrder.incrementAndGet();
                }
            }
            ValidationLog.check("recurrentcomplex.saveStructureDataOffThread", difference == null, file + ": " + difference);
            if (difference != null && mismatches.incrementAndGet() <= 10) {
                WorldgenStatus.LOG.error("[gambiarra] VALIDATION recurrentcomplex.saveStructureDataOffThread: {} differs from what the game would write: {}",
                        file, difference);
            }
        }
    }

    /** The first difference in content between what the game would write (a) and the file (b), key order aside; null if there is none. */
    static String firstDifference(String path, NBTBase a, NBTBase b) {
        if (a.getId() != b.getId()) {
            return (path.isEmpty() ? "/" : path) + ": type " + a.getId() + " in the game's, " + b.getId() + " in the file";
        }
        if (a instanceof NBTTagCompound) {
            NBTTagCompound ca = (NBTTagCompound) a;
            NBTTagCompound cb = (NBTTagCompound) b;
            for (String key : ca.getKeySet()) {
                if (!cb.hasKey(key)) {
                    return path + "/" + key + ": missing from the file";
                }
                String d = firstDifference(path + "/" + key, ca.getTag(key), cb.getTag(key));
                if (d != null) {
                    return d;
                }
            }
            for (String key : cb.getKeySet()) {
                if (!ca.hasKey(key)) {
                    return path + "/" + key + ": only in the file";
                }
            }
            return null;
        }
        if (a instanceof NBTTagList) {
            NBTTagList la = (NBTTagList) a;
            NBTTagList lb = (NBTTagList) b;
            if (la.tagCount() != lb.tagCount()) {
                return path + ": " + la.tagCount() + " elements in the game's, " + lb.tagCount() + " in the file";
            }
            for (int i = 0; i < la.tagCount(); i++) {
                String d = firstDifference(path + "[" + i + "]", la.get(i), lb.get(i));
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        return a.equals(b) ? null : path + ": " + a + " in the game's, " + b + " in the file";
    }

    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queued", queued.get());
        m.put("written", written.get());
        m.put("failures", failed.get());
        m.put("sizeBytes", bytes.get());
        m.put("writeMaxMs", maxWriteMs.get());
        m.put("writeTotalMs", totalWriteMs.get());
        if (VALIDATE) {
            m.put("checked", validated.get());
            m.put("sameBytes", sameBytes.get());
            m.put("sameContentOtherOrder", otherOrder.get());
            m.put("differences", mismatches.get());
        }
        return m;
    }

}
