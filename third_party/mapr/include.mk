# Copyright (C) 2015  The OpenTSDB Authors.
#
# This library is free software: you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as published
# by the Free Software Foundation, either version 2.1 of the License, or
# (at your option) any later version.
#
# This library is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with this library.  If not, see <http://www.gnu.org/licenses/>.

MAPR_VERSION := 5.1.0-mapr-20151124.213111-856
MAPR := third_party/mapr/maprfs-$(MAPR_VERSION).jar
MAPR_BASE_URL := http://maven.corp.maprtech.com/nexus/content/repositories/snapshots/com/mapr/hadoop/maprfs/5.1.0-mapr-SNAPSHOT/

$(MAPR): $(MAPR).md5
	set dummy "$(MAPR_BASE_URL)" "$(MAPR)"; shift; $(FETCH_DEPENDENCY)

THIRD_PARTY += $(MAPR)
