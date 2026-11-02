package com.iosbridge.iap2

/** iAP2 link envelope codec only; does not implement authentication or CarPlay. */
object Iap2LinkPacketCodec {
    const val START_MSB=0xFF; const val START_LSB=0x5A; const val HEADER_SIZE=9; const val MAX_PACKET_SIZE=0xFFFF
    const val CONTROL_SYN=0x80; const val CONTROL_ACK=0x40; const val CONTROL_EAK=0x20; const val CONTROL_RST=0x10; const val CONTROL_SLP=0x08
    data class Packet(val control:Int,val sequence:Int,val acknowledgement:Int,val sessionId:Int,val payload:ByteArray?=null) {
        init { require(control in 0..255 && sequence in 0..255 && acknowledgement in 0..255 && sessionId in 0..255) }
    }
    sealed class DecodeResult {
        data class Success(val packet:Packet):DecodeResult()
        data class Error(val reason:String):DecodeResult()
    }
    fun encode(packet:Packet):ByteArray {
        validateControl(packet.control,packet.payload!=null)?.let { throw IllegalArgumentException(it) }
        validateHeader(packet.control,packet.acknowledgement,packet.sessionId,packet.payload!=null)?.let { throw IllegalArgumentException(it) }
        val p=packet.payload
        val size=HEADER_SIZE+(if(p==null) 0 else p.size+1)
        require(size<=MAX_PACKET_SIZE)
        val out=ByteArray(size)
        out[0]=START_MSB.toByte(); out[1]=START_LSB.toByte(); out[2]=(size ushr 8).toByte(); out[3]=size.toByte()
        out[4]=packet.control.toByte(); out[5]=packet.sequence.toByte(); out[6]=packet.acknowledgement.toByte(); out[7]=packet.sessionId.toByte()
        out[8]=checksum(out,0,8).toByte()
        if(p!=null){p.copyInto(out,HEADER_SIZE);out[out.lastIndex]=checksum(p,0,p.size).toByte()}
        return out
    }
    fun decode(frame:ByteArray):DecodeResult {
        if(frame.size<HEADER_SIZE)return DecodeResult.Error("header incomplete")
        if(u(frame[0])!=START_MSB||u(frame[1])!=START_LSB)return DecodeResult.Error("start marker mismatch")
        val len=(u(frame[2]) shl 8) or u(frame[3])
        if(len!=frame.size)return DecodeResult.Error("length mismatch")
        if(checksum(frame,0,8)!=u(frame[8]))return DecodeResult.Error("header checksum mismatch")
        val c=u(frame[4]); val has= len>HEADER_SIZE
        validateControl(c,has)?.let{return DecodeResult.Error(it)}
        validateHeader(c,u(frame[6]),u(frame[7]),has)?.let{return DecodeResult.Error(it)}
        val payload=if(has){val data=frame.copyOfRange(HEADER_SIZE,frame.lastIndex);if(checksum(data,0,data.size)!=u(frame.last()))return DecodeResult.Error("payload checksum mismatch");data}else null
        return DecodeResult.Success(Packet(c,u(frame[5]),u(frame[6]),u(frame[7]),payload))
    }
    private fun validateControl(c:Int,has:Boolean):String? {
        val syn=c and CONTROL_SYN!=0; val ack=c and CONTROL_ACK!=0; val eak=c and CONTROL_EAK!=0; val rst=c and CONTROL_RST!=0; val slp=c and CONTROL_SLP!=0
        if(c and 7!=0)return "reserved control bits"
        if(eak&&rst)return "EAK and RST conflict"
        if(syn&&(eak||rst||slp))return "invalid SYN combination"
        if(rst&&(ack||slp)||slp&&(ack||eak||rst))return "invalid control combination"
        if(eak&&!ack)return "EAK requires ACK"
        if(syn&&!has)return "SYN requires payload"
        if(eak&&!has)return "EAK requires payload"
        if(rst&&(c!=CONTROL_RST||has))return "invalid RST"
        if(slp&&(c!=CONTROL_SLP||has))return "invalid SLP"
        if(has&&!(ack||syn||eak))return "payload requires ACK, SYN, or EAK"
        return null
    }
    private fun validateHeader(c:Int,a:Int,s:Int,has:Boolean):String? {
        if(c and CONTROL_ACK==0&&a!=0)return "ACK number must be zero when ACK clear"
        if(c!=CONTROL_ACK&&s!=0)return "session ID must be zero outside ACK session packet"
        if(!has&&s!=0)return "session ID must be zero without payload"
        return null
    }
    private fun checksum(b:ByteArray,o:Int,n:Int):Int {var sum=0;for(i in o until o+n)sum=(sum+u(b[i])) and 255;return (-sum) and 255}
    private fun u(b:Byte)=b.toInt() and 255

    /** Incremental frame parser for arbitrary stream read boundaries. */
    class StreamDecoder(private val maxBufferedBytes:Int=MAX_PACKET_SIZE*2) {
        private var pending=ByteArray(0)
        init { require(maxBufferedBytes>=MAX_PACKET_SIZE) }
        @Synchronized fun feed(bytes:ByteArray):List<DecodeResult> {
            if(bytes.isEmpty())return emptyList()
            pending+=bytes
            if(pending.size>maxBufferedBytes)pending=if(pending.lastOrNull()?.let{u(it)==START_MSB}==true) byteArrayOf(pending.last()) else byteArrayOf()
            val results=ArrayList<DecodeResult>()
            while(true){
                var start=-1
                for(i in 0 until pending.size-1)if(u(pending[i])==START_MSB&&u(pending[i+1])==START_LSB){start=i;break}
                if(start<0){pending=if(pending.lastOrNull()?.let{u(it)==START_MSB}==true)byteArrayOf(pending.last())else byteArrayOf();break}
                if(start>0)pending=pending.copyOfRange(start,pending.size)
                if(pending.size<4)break
                val len=(u(pending[2]) shl 8) or u(pending[3])
                if(len<HEADER_SIZE){results+=DecodeResult.Error("invalid declared packet length");pending=pending.copyOfRange(1,pending.size);continue}
                if(pending.size<len)break
                when(val decoded=decode(pending.copyOfRange(0,len))){
                    is DecodeResult.Success->{results+=decoded;pending=pending.copyOfRange(len,pending.size)}
                    is DecodeResult.Error->{results+=decoded;pending=pending.copyOfRange(1,pending.size)}
                }
            }
            return results
        }
        @Synchronized fun bufferedByteCount()=pending.size
    }
}
