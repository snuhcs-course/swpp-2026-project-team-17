package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class TextEntryMetricsTest {
    @Test fun hangulNfcAndOuterWhitespaceAreNormalized(){assertEquals(0,TextEntryMetrics.distance("가"," 가 "))}
    @Test fun insertionDeletionAndSubstitutionAreCounted(){assertEquals(1,TextEntryMetrics.distance("abc","axc"));assertEquals(1,TextEntryMetrics.distance("abc","ab"));assertEquals(2,TextEntryMetrics.distance("abc","abcde"))}
    @Test fun internalSpacesAndPunctuationRemainSignificant(){assertEquals(1,TextEntryMetrics.distance("안녕.","안녕"));assertEquals(1,TextEntryMetrics.distance("a b","ab"))}
    @Test fun unicodeCodePointsAndEmptyResponse(){assertEquals(1,TextEntryMetrics.distance("😀","😁"));assertEquals(1.0,TextEntryMetrics.cer("안녕",""),0.0);assertEquals(2.0,TextEntryMetrics.cer("a","abc"),0.0)}
    @Test fun evaluationProbeDoesNotRetainEvents(){var count=0;TextEntryProbe.listener={count++};TextEntryProbe.emit("writing_opened");TextEntryProbe.listener=null;TextEntryProbe.emit("voice_ready");assertEquals(1,count)}
    @Test fun sandboxAdapterIsRestrictedToItsOwnField(){assertTrue(MessagingEditors.supports("dev.gazedot","dev.gazedot:id/eval_editor",false,1));assertFalse(MessagingEditors.supports("dev.gazedot","dev.gazedot:id/password",false,1));assertFalse(MessagingEditors.supports("dev.gazedot","dev.gazedot:id/eval_editor",true,1))}
}
