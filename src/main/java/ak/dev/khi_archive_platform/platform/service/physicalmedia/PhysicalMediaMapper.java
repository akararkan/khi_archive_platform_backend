package ak.dev.khi_archive_platform.platform.service.physicalmedia;

import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaCreateRequestDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaResponseDTO;
import ak.dev.khi_archive_platform.platform.dto.physicalmedia.PhysicalMediaUpdateRequestDTO;
import ak.dev.khi_archive_platform.platform.enums.DigitizationStatus;
import ak.dev.khi_archive_platform.platform.model.physicalmedia.PhysicalMedia;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ DTO translator. Two responsibilities:
 * <ol>
 *   <li>{@link #toResponse(PhysicalMedia)} — drops the entity onto the wire,
 *       exposing the digitization / needToClear values in both their typed
 *       and numeric forms so the frontend can pick whichever is convenient.</li>
 *   <li>{@code applyCreate / applyUpdate} — copies DTO fields onto a
 *       managed entity. The create variant overwrites unconditionally; the
 *       update variant only touches fields whose JSON value is non-null,
 *       which is the standard PATCH semantic.</li>
 * </ol>
 *
 * <p>Both apply variants resolve {@code digitizationCode} → enum and
 * {@code needToClearCode} → boolean when the typed field is absent — that
 * lets a client paste a row straight from the sheet without converting.
 */
@Component
public class PhysicalMediaMapper {

    public PhysicalMediaResponseDTO toResponse(PhysicalMedia e) {
        if (e == null) return null;
        return PhysicalMediaResponseDTO.builder()
                .id(e.getId())
                .pmCode(e.getPmCode())
                .rowNumber(e.getRowNumber())
                .inventoryNumber(e.getInventoryNumber())
                .physicalMediaType(e.getPhysicalMediaType())
                .mediaCategory(e.getMediaCategory())
                .title(e.getTitle())
                .subType(e.getSubType())
                .physicalLabel(e.getPhysicalLabel())
                .size(e.getSize())
                .content(e.getContent())
                .archiveDepNote(e.getArchiveDepNote())
                .digitization(e.getDigitization())
                .digitizationCode(e.getDigitization() == null ? null : e.getDigitization().getCode())
                .owner(e.getOwner())
                .year(e.getYear())
                .durationMin(e.getDurationMin())
                .trackNumbers(e.getTrackNumbers())
                .trackName(e.getTrackName())
                .extension(e.getExtension())
                .bitOrColorDepth(e.getBitOrColorDepth())
                .sampleOrFrameRate(e.getSampleOrFrameRate())
                .channelsOrResolution(e.getChannelsOrResolution())
                .playbackModel(e.getPlaybackModel())
                .captureInterface(e.getCaptureInterface())
                .signalInterface(e.getSignalInterface())
                .ingestSoftware(e.getIngestSoftware())
                .formatCodec(e.getFormatCodec())
                .digitizeDate(e.getDigitizeDate())
                .tags(e.getTags())
                .needToClear(e.getNeedToClear())
                .needToClearCode(e.getNeedToClear() == null ? null
                        : (e.getNeedToClear() ? 1 : 0))
                .captureDepNote(e.getCaptureDepNote())
                .source(e.getSource())
                .createdBy(e.getCreatedBy())
                .updatedBy(e.getUpdatedBy())
                .removedBy(e.getRemovedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .removedAt(e.getRemovedAt())
                .version(e.getVersion())
                .build();
    }

    /** Unconditional overwrite (used on create). */
    public void applyCreate(PhysicalMedia e, PhysicalMediaCreateRequestDTO dto) {
        e.setRowNumber(dto.getRowNumber());
        e.setInventoryNumber(dto.getInventoryNumber());
        e.setPhysicalMediaType(trimOrNull(dto.getPhysicalMediaType()));
        e.setMediaCategory(trimOrNull(dto.getMediaCategory()));
        e.setTitle(trimOrNull(dto.getTitle()));
        e.setSubType(trimOrNull(dto.getSubType()));
        e.setPhysicalLabel(trimOrNull(dto.getPhysicalLabel()));
        e.setSize(trimOrNull(dto.getSize()));
        e.setContent(trimOrNull(dto.getContent()));
        e.setArchiveDepNote(trimOrNull(dto.getArchiveDepNote()));
        e.setDigitization(resolveDigitization(dto.getDigitization(), dto.getDigitizationCode()));
        e.setOwner(trimOrNull(dto.getOwner()));
        e.setYear(dto.getYear());
        e.setDurationMin(dto.getDurationMin());
        e.setTrackNumbers(dto.getTrackNumbers());
        e.setTrackName(trimOrNull(dto.getTrackName()));
        e.setExtension(trimOrNull(dto.getExtension()));
        e.setBitOrColorDepth(trimOrNull(dto.getBitOrColorDepth()));
        e.setSampleOrFrameRate(trimOrNull(dto.getSampleOrFrameRate()));
        e.setChannelsOrResolution(trimOrNull(dto.getChannelsOrResolution()));
        e.setPlaybackModel(trimOrNull(dto.getPlaybackModel()));
        e.setCaptureInterface(trimOrNull(dto.getCaptureInterface()));
        e.setSignalInterface(trimOrNull(dto.getSignalInterface()));
        e.setIngestSoftware(trimOrNull(dto.getIngestSoftware()));
        e.setFormatCodec(trimOrNull(dto.getFormatCodec()));
        e.setDigitizeDate(dto.getDigitizeDate());
        e.setTags(trimOrNull(dto.getTags()));
        e.setNeedToClear(resolveNeedToClear(dto.getNeedToClear(), dto.getNeedToClearCode()));
        e.setCaptureDepNote(trimOrNull(dto.getCaptureDepNote()));
    }

    /** PATCH semantics: only touch fields the caller actually sent.
     *  Returns the list of field names that were touched so the audit
     *  detail can name them ("fields=title,owner,year"). */
    public java.util.List<String> applyUpdate(PhysicalMedia e, PhysicalMediaUpdateRequestDTO dto) {
        java.util.List<String> touched = new java.util.ArrayList<>();
        if (dto.getRowNumber() != null)        { e.setRowNumber(dto.getRowNumber()); touched.add("rowNumber"); }
        if (dto.getInventoryNumber() != null)  { e.setInventoryNumber(dto.getInventoryNumber()); touched.add("inventoryNumber"); }
        if (dto.getPhysicalMediaType() != null){ e.setPhysicalMediaType(trimOrNull(dto.getPhysicalMediaType())); touched.add("physicalMediaType"); }
        if (dto.getMediaCategory() != null)    { e.setMediaCategory(trimOrNull(dto.getMediaCategory())); touched.add("mediaCategory"); }
        if (dto.getTitle() != null)            { e.setTitle(trimOrNull(dto.getTitle())); touched.add("title"); }
        if (dto.getSubType() != null)          { e.setSubType(trimOrNull(dto.getSubType())); touched.add("subType"); }
        if (dto.getPhysicalLabel() != null)    { e.setPhysicalLabel(trimOrNull(dto.getPhysicalLabel())); touched.add("physicalLabel"); }
        if (dto.getSize() != null)             { e.setSize(trimOrNull(dto.getSize())); touched.add("size"); }
        if (dto.getContent() != null)          { e.setContent(trimOrNull(dto.getContent())); touched.add("content"); }
        if (dto.getArchiveDepNote() != null)   { e.setArchiveDepNote(trimOrNull(dto.getArchiveDepNote())); touched.add("archiveDepNote"); }
        if (dto.getDigitization() != null || dto.getDigitizationCode() != null) {
            e.setDigitization(resolveDigitization(dto.getDigitization(), dto.getDigitizationCode()));
            touched.add("digitization");
        }
        if (dto.getOwner() != null)            { e.setOwner(trimOrNull(dto.getOwner())); touched.add("owner"); }
        if (dto.getYear() != null)             { e.setYear(dto.getYear()); touched.add("year"); }
        if (dto.getDurationMin() != null)      { e.setDurationMin(dto.getDurationMin()); touched.add("durationMin"); }
        if (dto.getTrackNumbers() != null)     { e.setTrackNumbers(dto.getTrackNumbers()); touched.add("trackNumbers"); }
        if (dto.getTrackName() != null)        { e.setTrackName(trimOrNull(dto.getTrackName())); touched.add("trackName"); }
        if (dto.getExtension() != null)        { e.setExtension(trimOrNull(dto.getExtension())); touched.add("extension"); }
        if (dto.getBitOrColorDepth() != null)  { e.setBitOrColorDepth(trimOrNull(dto.getBitOrColorDepth())); touched.add("bitOrColorDepth"); }
        if (dto.getSampleOrFrameRate() != null){ e.setSampleOrFrameRate(trimOrNull(dto.getSampleOrFrameRate())); touched.add("sampleOrFrameRate"); }
        if (dto.getChannelsOrResolution() != null) { e.setChannelsOrResolution(trimOrNull(dto.getChannelsOrResolution())); touched.add("channelsOrResolution"); }
        if (dto.getPlaybackModel() != null)    { e.setPlaybackModel(trimOrNull(dto.getPlaybackModel())); touched.add("playbackModel"); }
        if (dto.getCaptureInterface() != null) { e.setCaptureInterface(trimOrNull(dto.getCaptureInterface())); touched.add("captureInterface"); }
        if (dto.getSignalInterface() != null)  { e.setSignalInterface(trimOrNull(dto.getSignalInterface())); touched.add("signalInterface"); }
        if (dto.getIngestSoftware() != null)   { e.setIngestSoftware(trimOrNull(dto.getIngestSoftware())); touched.add("ingestSoftware"); }
        if (dto.getFormatCodec() != null)      { e.setFormatCodec(trimOrNull(dto.getFormatCodec())); touched.add("formatCodec"); }
        if (dto.getDigitizeDate() != null)     { e.setDigitizeDate(dto.getDigitizeDate()); touched.add("digitizeDate"); }
        if (dto.getTags() != null)             { e.setTags(trimOrNull(dto.getTags())); touched.add("tags"); }
        if (dto.getNeedToClear() != null || dto.getNeedToClearCode() != null) {
            e.setNeedToClear(resolveNeedToClear(dto.getNeedToClear(), dto.getNeedToClearCode()));
            touched.add("needToClear");
        }
        if (dto.getCaptureDepNote() != null)   { e.setCaptureDepNote(trimOrNull(dto.getCaptureDepNote())); touched.add("captureDepNote"); }
        return touched;
    }

    private static DigitizationStatus resolveDigitization(DigitizationStatus typed, Integer code) {
        if (typed != null) return typed;
        return DigitizationStatus.fromCode(code);
    }

    private static Boolean resolveNeedToClear(Boolean typed, Integer code) {
        if (typed != null) return typed;
        if (code == null) return null;
        if (code == 0) return Boolean.FALSE;
        if (code == 1) return Boolean.TRUE;
        throw new IllegalArgumentException("needToClearCode must be 0 or 1, got: " + code);
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
