# JDT Search Support for Declarative Services

JDT Search makes it easy to find references to various Java elements across your codebase. But what about DS component descriptor files?

This project delivers an Eclipse plugin that extends the reach of JDT's search functionality into DS component descriptor files -- any references to classes, interfaces or methods are identified and located.

## Installation

A public p2 repository with this feature is available at http://download.eclipticalsoftware.com/updates/.

## Usage

Whenever you search for references to Java types or methods, DS component descriptor files are automatically included in the search; if the Java element you're looking for is referenced in the file, that file is include in search results. 

## License

This software is made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html.

----------------------------------------------------------------------
Copyright (c) 2015 Ecliptical Software Inc. All rights reserved.
