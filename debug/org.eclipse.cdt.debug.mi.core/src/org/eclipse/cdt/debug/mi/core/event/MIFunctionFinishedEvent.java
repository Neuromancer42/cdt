/*
 * (c) Copyright QNX Software Systems Ltd. 2002.
 * All Rights Reserved.
 *
 */
package org.eclipse.cdt.debug.mi.core.event;

import org.eclipse.cdt.debug.mi.core.output.MIConst;
import org.eclipse.cdt.debug.mi.core.output.MIExecAsyncOutput;
import org.eclipse.cdt.debug.mi.core.output.MIFrame;
import org.eclipse.cdt.debug.mi.core.output.MIResult;
import org.eclipse.cdt.debug.mi.core.output.MIResultRecord;
import org.eclipse.cdt.debug.mi.core.output.MITuple;
import org.eclipse.cdt.debug.mi.core.output.MIValue;

/**
 * *stopped,reason="function-finished",thread-id="0",frame={addr="0x0804855a",func="main",args=[{name="argc",value="1"},{name="argv",value="0xbffff18c"}],file="hello.c",line="17"},gdb-result-var="$1",return-value="10"
 */
public class MIFunctionFinishedEvent extends MIStoppedEvent {

	String gdbResult = "";
	String returnValue = "";

	public MIFunctionFinishedEvent(MIExecAsyncOutput async) {
		super(async);
		parse();
	}

	public MIFunctionFinishedEvent(MIResultRecord record) {
		super(record);
		parse();
	}

	public String getGDBResultVar() {
		return gdbResult;
	}

	public String getReturnValue() {
		return returnValue;
	}

	public String toString() {
		StringBuffer buffer = new StringBuffer();
		buffer.append("gdb-result-var=" + gdbResult + "\n");;
		buffer.append("return-value=" + returnValue + "\n");
		buffer.append("thread-id=").append(getThreadId()).append('\n');
		MIFrame f = getFrame();
		if (f != null) {
			buffer.append(f.toString());
		}
		return buffer.toString();
	}

	void parse () {
		MIExecAsyncOutput exec = getMIExecAsyncOutput();
		MIResultRecord rr = getMIResultRecord();

		MIResult[] results = null;
		if (exec != null) {
			results = exec.getMIResults();
		} else if (rr != null) {
			results = rr.getMIResults();
		}
		if (results != null) {
			for (int i = 0; i < results.length; i++) {
				String var = results[i].getVariable();
				MIValue value = results[i].getMIValue();
				String str = "";
				if (value instanceof MIConst) {
					str = ((MIConst)value).getString();
				}

				if (var.equals("gdb-result-var")) {
					gdbResult = str;
				} else if (var.equals("return-value")) {
					returnValue = str;
				} else if (var.equals("thread-id")) {
					try {
						int id = Integer.parseInt(str.trim());
						setThreadId(id);
					} catch (NumberFormatException e) {
					}
				} else if (var.equals("frame")) {
					if (value instanceof MITuple) {
						MIFrame f = new MIFrame((MITuple)value);
						setFrame(f);
					}
				}
			}
		}
	}
}
