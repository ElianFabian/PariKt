package sample

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

internal suspend fun smoke() {
	PariKt.init()
	try {
		val actual = PariKt.getVersionName()
		if (expectedVersion !in setOf("master", "latest")) check(actual == expectedVersion) {
			"Consumer requested $expectedVersion but loaded $actual"
		}
		check(PariKt.eval("2+2").substringAfter("= ").trim() == "4")
		check(PariKt.eval("factor(123456)==[2,6;3,1;643,1]").substringAfter("= ").trim() == "1")
		println("Consumer PARI $actual: arithmetic and factorization passed")
	} finally {
		PariKt.close()
	}
}
